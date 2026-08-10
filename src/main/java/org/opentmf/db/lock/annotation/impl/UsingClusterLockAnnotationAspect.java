package org.opentmf.db.lock.annotation.impl;

import org.opentmf.db.lock.annotation.UsingClusterLock;
import org.opentmf.db.lock.exception.DbLockException;
import org.opentmf.db.lock.model.AcquiredLock;
import org.opentmf.db.lock.model.LockContext;
import org.opentmf.db.lock.service.api.DbLockService;
import java.time.Duration;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.core.env.Environment;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.stereotype.Component;

/**
 * @author Abdullah Beker
 */
@Aspect
@Component
@RequiredArgsConstructor
@Slf4j
public class UsingClusterLockAnnotationAspect {

  private final Environment environment;
  private final DbLockService dbLockService;

  @Around("@annotation(usingClusterLock)")
  public Object wrapWithLock(ProceedingJoinPoint pjp, UsingClusterLock usingClusterLock)
      throws Throwable {
    String requestedVersion = resolveProperty(usingClusterLock.requestedVersion());
    long downgradeAllowedMillis =
        Duration.parse(resolveProperty(usingClusterLock.downgradeAllowedAfter())).toMillis();
    String failureMessage = usingClusterLock.failureMessage();

    boolean lockReleased = false;
    AcquiredLock lock = null;
    try {
      lock = dbLockService.acquireLock(usingClusterLock.lockType(), requestedVersion);

      if (shouldExecute(lock, usingClusterLock, downgradeAllowedMillis)) {
        LockContext ctx = enrichFirstLockContextIfAny(pjp, lock);

        // Execute the actual business logic
        Object result = pjp.proceed(pjp.getArgs());

        // Release the lock. If a LockContext was threaded through, let the method's
        // outcome determine whether the latest-lock record should be updated.
        dbLockService.releaseLock(lock, ctx == null || ctx.isSuccess());
        lockReleased = true;

        return result;
      }

      log.debug("Requested lock version is already the latest. Not calling service method.");
      dbLockService.releaseLock(lock, false);
      lockReleased = true;

    } catch (Exception e) {
      if (lock != null) {
        releaseSuppressing(lock, e);
        lockReleased = true;
      }
      if (!failureMessage.isEmpty()) {
        throw new IllegalStateException(failureMessage, e);
      }
      throw e;
    } finally {
      // Safety net for a Throwable the catch above does not handle -- an Error thrown by the
      // advised method would otherwise leave the lock held until its hold-timeout expires.
      if (!lockReleased && lock != null) {
        releaseQuietly(lock);
      }
    }
    return null;
  }

  /**
   * Whether the advised method should run for this lock: an upgrade always, a downgrade only once
   * the configured grace period has passed, and a re-run of the same version only when the
   * annotation opts in.
   */
  private boolean shouldExecute(AcquiredLock lock, UsingClusterLock usingClusterLock,
      long downgradeAllowedMillis) {
    return lock.isUpgrade()
        || (lock.isDowngrade() && lock.isDowngradeAllowed(downgradeAllowedMillis))
        || (lock.isSameVersion() && usingClusterLock.executeOnSameVersion());
  }

  /**
   * Releases the lock while a failure is already propagating, attaching any release failure to it
   * so the original cause stays the primary exception.
   */
  private void releaseSuppressing(AcquiredLock lock, Throwable primary) {
    try {
      dbLockService.releaseLock(lock, false);
    } catch (DbLockException releaseEx) {
      primary.addSuppressed(releaseEx);
    }
  }

  /** Best-effort release; the primary failure has already been propagated. */
  private void releaseQuietly(AcquiredLock lock) {
    try {
      dbLockService.releaseLock(lock, false);
    } catch (DbLockException ignored) {
      // nothing useful to do here -- the caller is already unwinding
    }
  }

  private String resolveProperty(String value) throws DbLockException {
    if (value.startsWith("${")) {
      if (!value.endsWith("}")) {
        throw new DbLockException("Malformed property placeholder: '" + value + "'");
      }
      try {
        return environment.resolveRequiredPlaceholders(value);
      } catch (IllegalArgumentException e) {
        throw new DbLockException(e.getMessage());
      }
    }
    if (value.startsWith("#{")) {
      if (!value.endsWith("}")) {
        throw new DbLockException("Malformed SpEL expression: '" + value + "'");
      }
      return new SpelExpressionParser().parseExpression(parseValue(value)).getValue(String.class);
    }
    return value;
  }

  private String parseValue(String value) {
    return value.substring(2, value.length() - 1).trim();
  }

  private LockContext enrichFirstLockContextIfAny(ProceedingJoinPoint joinPoint,
      AcquiredLock lock) {
    for (Object methodArg : joinPoint.getArgs()) {
      if (methodArg instanceof LockContext ctx) {
        ctx.setLatestLock(lock.getPreviousLock());
        ctx.setVersionTransition(lock.getVersionTransition());
        ctx.setRequestedVersion(lock.getLockVersion());
        return ctx;
      }
    }
    return null;
  }
}
