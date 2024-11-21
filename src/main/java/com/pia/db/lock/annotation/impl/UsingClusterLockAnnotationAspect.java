package com.pia.db.lock.annotation.impl;

import com.pia.db.lock.annotation.UsingClusterLock;
import com.pia.db.lock.model.AcquiredLock;
import com.pia.db.lock.model.LockContext;
import com.pia.db.lock.service.api.DbLockService;
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
  private Object wrapWithLock(ProceedingJoinPoint pjp, UsingClusterLock usingClusterLock)
      throws Throwable {
    String requestedVersion = resolveProperty(usingClusterLock.requestedVersion());
    long downgradeAllowedMillis =
        Long.parseLong(resolveProperty(usingClusterLock.downgradeAllowedMillis()));

    boolean lockReleased = false;
    AcquiredLock lock = null;
    try {
      lock = dbLockService.acquireLock(usingClusterLock.lockType(), requestedVersion);

      if (lock.isUpgrade() ||
          (lock.isDowngrade() && lock.isDowngradeAllowed(downgradeAllowedMillis)) ||
          (lock.isSameVersion() && usingClusterLock.executeOnSameVersion())) {

        // Execute the actual business logic
        Object result = pjp.proceed(enrichFirstLockContextIfAny(pjp, lock));

        // No exception from the service method means we have a successful completion.
        // Release the lock and update latest_lock record.
        dbLockService.releaseLock(lock, true);
        lockReleased = true;

        return result;
      } else {
        log.debug("Requested lock version is already the latest. Not calling service method.");
        dbLockService.releaseLock(lock, false);
        lockReleased = true;
      }

    } catch (Exception e) {
      if (lock != null) {
        dbLockService.releaseLock(lock, false);
        lockReleased = true;
      }
      throw e;
    } finally {
      if (!lockReleased && lock != null) {
        dbLockService.releaseLock(lock, false);
      }
    }
    return null;
  }

  private String resolveProperty(String value) {
    if (value.startsWith("${") && value.endsWith("}")) {
      return environment.getProperty(parseValue(value));
    }
    if (value.startsWith("#{") && value.endsWith("}")) {
      return new SpelExpressionParser().parseExpression(parseValue(value)).getValue(String.class);
    }
    return value;
  }

  private String parseValue(String value) {
    return value.substring(2, value.length() - 1).trim();
  }

  private Object[] enrichFirstLockContextIfAny(ProceedingJoinPoint joinPoint, AcquiredLock lock) {
    Object[] methodArgs = joinPoint.getArgs();
    for (Object methodArg : methodArgs) {
      if (methodArg instanceof LockContext ctx) {
        ctx.setLatestLock(lock.getPreviousLock());
        ctx.setVersionTransition(lock.getVersionTransition());
        ctx.setRequestedVersion(lock.getLockVersion());
        break;
      }
    }
    return methodArgs;
  }
}
