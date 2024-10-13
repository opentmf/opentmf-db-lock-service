package com.pia.db.lock.annotation;

import com.pia.db.lock.model.LockType;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Annotation that enables the code to be executed within a cluster level db lock
 * Mandatory fields:
 * - lockType: Lock type to be used.
 * - requestedVersion: The version of the lock to be retrieved.
 *
 * @author Abdullah Beker
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface UsingClusterLock {

  /**
   * Lock type to be used.
   */
  LockType lockType();

  /**
   * The version of the lock to be retrieved.
   *
   * @see com.pia.db.lock.service.api.DbLockService#acquireLock(LockType, String)
   */
  String requestedVersion();

  /**
   * Maximum allowed duration in milliseconds to allow a downgrade. Defaults to 10 minutes if not specified.
   *
   * @see com.pia.db.lock.model.AcquiredLock#isDowngradeRequired(String, long)
   */
  String downgradeAllowedMillis() default "600000";
}
