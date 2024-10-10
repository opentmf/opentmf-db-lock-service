package com.pia.db.lock.annotation.impl;

import com.pia.db.lock.annotation.WithLock;
import com.pia.db.lock.model.AcquiredLock;
import com.pia.db.lock.model.LockType;
import com.pia.db.lock.service.api.DbLockService;
import lombok.RequiredArgsConstructor;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

@Aspect
@Component
@RequiredArgsConstructor
public class WithLockAnnotationAspect {

    private final Environment environment;
    private final DbLockService dbLockService;

    @Around("(@annotation(withLock))")
    private Object wrapWithLock(ProceedingJoinPoint pjp, WithLock withLock) throws Throwable {
        String requestedVersion = resolveProperty(withLock.requestedVersion());
        long downgradeAllowedMillis = Long.parseLong(resolveProperty(withLock.downgradeAllowedMillis()));

        boolean lockReleased = false;
        AcquiredLock lock = null;
        try {
            lock = dbLockService.acquireLock(LockType.LOCK_X, requestedVersion);

            if (lock.isUpgradeRequired(requestedVersion) ||
                    lock.isDowngradeRequired(requestedVersion, downgradeAllowedMillis)) {

                Object result = pjp.proceed();  // Execute the actual business logic

                dbLockService.releaseLock(lock, true);  // Release the lock after completing the task
                lockReleased = true;

                return result;
            } else {
                dbLockService.releaseLock(lock, false);
                lockReleased = true;
            }
        } catch (Exception e) {
            dbLockService.releaseLock(lock, false);
            lockReleased = true;
            throw e;
        } finally {
            if (!lockReleased) {
                dbLockService.releaseLock(lock, false);
            }
        }
        return null;
    }

    private String resolveProperty(String value) {
        if (value.startsWith("${") && value.endsWith("}")) {
            String propertyKey = value.substring(2, value.length() - 1);
            return environment.getProperty(propertyKey);
        }
        return value;
    }
}
