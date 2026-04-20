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

      if (lock.isUpgrade() ||
          (lock.isDowngrade() && lock.isDowngradeAllowed(downgradeAllowedMillis)) ||
          (lock.isSameVersion() && usingClusterLock.executeOnSameVersion())) {

        LockContext ctx = enrichFirstLockContextIfAny(pjp, lock);

        // Execute the actual business logic
        Object result = pjp.proceed(pjp.getArgs());

        // Release the lock. If a LockContext was threaded through, let the method's
        // outcome determine whether the latest-lock record should be updated.
        dbLockService.releaseLock(lock, ctx == null || ctx.isSuccess());
        lockReleased = true;

        return result;
      } else {
        log.debug("Requested lock version is already the latest. Not calling service method.");
        dbLockService.releaseLock(lock, false);
        lockReleased = true;
      }

    } catch (Exception e) {
      if (lock != null) {
        try {
          dbLockService.releaseLock(lock, false);
        } catch (DbLockException releaseEx) {
          e.addSuppressed(releaseEx);
        }
        lockReleased = true;
      }
      if (!failureMessage.isEmpty()) {
        throw new IllegalStateException(failureMessage, e);
      }
      throw e;
    } finally {
      if (!lockReleased && lock != null) {
        try {
          dbLockService.releaseLock(lock, false);
        } catch (DbLockException ignored) {
          // best-effort cleanup; primary failure has already been propagated
        }
      }
    }
    return null;
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
