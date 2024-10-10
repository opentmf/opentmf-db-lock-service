package com.pia.db.lock.annotation;

import com.pia.db.lock.model.LockType;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * author Abdullah Beker
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface UsingClusterLock {

    LockType lockType();
    boolean saveHistoryOnSuccess() default true;
    String requestedVersion();
    String downgradeAllowedMillis() default "0L";
}
