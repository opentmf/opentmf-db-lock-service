package com.pia.db.lock.annotation.impl;

import com.pia.db.lock.annotation.UsingClusterLock;
import com.pia.db.lock.model.AcquiredLock;
import com.pia.db.lock.service.api.DbLockService;
import lombok.RequiredArgsConstructor;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.core.env.Environment;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.stereotype.Component;

/**
 * author Abdullah Beker
 */
@Aspect
@Component
@RequiredArgsConstructor
public class UsingClusterLockAnnotationAspect {

    private final Environment environment;
    private final DbLockService dbLockService;

    @Around("(@annotation(usingClusterLock))")
    private Object wrapWithLock(ProceedingJoinPoint pjp, UsingClusterLock usingClusterLock) throws Throwable {
        String requestedVersion = resolveProperty(usingClusterLock.requestedVersion());
        long downgradeAllowedMillis = Long.parseLong(resolveProperty(usingClusterLock.downgradeAllowedMillis()));

        boolean lockReleased = false;
        AcquiredLock lock = null;
        try {
            lock = dbLockService.acquireLock(usingClusterLock.lockType(), requestedVersion);

            if (lock.isUpgradeRequired(requestedVersion) ||
                    lock.isDowngradeRequired(requestedVersion, downgradeAllowedMillis)) {

                Object result = pjp.proceed();  // Execute the actual business logic

                dbLockService.releaseLock(lock, usingClusterLock.saveHistoryOnSuccess());  // Release the lock after completing the task
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
}
