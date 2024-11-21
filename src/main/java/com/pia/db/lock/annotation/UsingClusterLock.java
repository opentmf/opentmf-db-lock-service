package com.pia.db.lock.annotation;

import com.pia.db.lock.model.AcquiredLock;
import com.pia.db.lock.model.LockType;
import com.pia.db.lock.service.api.DbLockService;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Annotation that enables the code to be executed within a cluster level db lock.
 *
 * <p>Mandatory fields:
 *
 * <ul>
 *   <li><strong>lockType:</strong> Lock type to be used.
 *   <li><strong>requestedVersion:</strong> The version of the lock to be retrieved.
 * </ul>
 *
 * @author Abdullah Beker
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface UsingClusterLock {

  /** Lock type to be used. */
  LockType lockType();

  /**
   * The version requested to be synchronized to.
   *
   * @see DbLockService#acquireLock(LockType, String)
   */
  String requestedVersion();

  /**
   * Maximum allowed duration in milliseconds to allow a downgrade. Defaults to 10 minutes if not
   * specified.
   *
   * @see AcquiredLock#isDowngradeAllowed(long)
   */
  String downgradeAllowedMillis() default "600000";

  /**
   * When set to <b>true</b> also executes the annotated method if the requested version is the same
   * with the previous lock version. Default value is false.
   */
  boolean executeOnSameVersion() default false;
}
