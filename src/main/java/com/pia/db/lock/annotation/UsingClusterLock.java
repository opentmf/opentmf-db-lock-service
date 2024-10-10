package com.pia.db.lock.annotation;

import com.pia.db.lock.model.LockType;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * @author Abdullah Beker
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface UsingClusterLock {

  /**
   * Lock type to be used.
   * */
  LockType lockType();

  /**
   * If true, a record will be inserted into lock history table after the task is completed
   * successfully.
   */
  boolean saveHistoryOnSuccess() default true;

  /**
   * The version of the lock to be retrieved.
   *
   * @see com.pia.db.lock.service.api.DbLockService#acquireLock(LockType, String)
   */
  String requestedVersion();

  /**
   * Maximum allowed duration in milliseconds to allow a downgrade.
   *
   * @see com.pia.db.lock.model.AcquiredLock#isDowngradeRequired(String, long)
   */
  String downgradeAllowedMillis() default "0L";
}
