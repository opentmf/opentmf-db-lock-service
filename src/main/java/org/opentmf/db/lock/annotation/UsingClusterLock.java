package org.opentmf.db.lock.annotation;

import org.opentmf.db.lock.exception.DbLockException;
import org.opentmf.db.lock.model.AcquiredLock;
import org.opentmf.db.lock.model.LockType;
import org.opentmf.db.lock.service.api.DbLockService;
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
 * <p>When the annotated method declares a {@link org.opentmf.db.lock.model.LockContext}
 * parameter, the aspect populates it with details of the current run and also honours
 * {@link org.opentmf.db.lock.model.LockContext#isSuccess()} on return: if the method
 * calls {@code context.setSuccess(false)} before returning, the lock is released
 * <em>without</em> recording the requested version as the latest successful one. Throwing
 * an exception always releases the lock with success=false regardless of the context.
 *
 * <p>When {@link #failureMessage()} is non-empty, any exception raised by the aspect
 * (including {@code DbLockException} from {@code acquireLock}) or by the annotated
 * method is wrapped as {@code new IllegalStateException(failureMessage, cause)} before
 * being propagated. This removes the need for callers to catch checked
 * {@code DbLockException} themselves.
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
   * Minimum time that must have passed since the last release before a downgrade is allowed,
   * expressed as an ISO-8601 duration string (e.g. {@code "PT10M"} for ten minutes,
   * {@code "PT2H"} for two hours). Property placeholders ({@code ${...}}) and SpEL expressions
   * ({@code #{...}}) are resolved first. Defaults to 10 minutes if not specified.
   *
   * @see AcquiredLock#isDowngradeAllowed(long)
   * @see java.time.Duration#parse(CharSequence)
   */
  String downgradeAllowedAfter() default "PT10M";

  /**
   * When set to <b>true</b> also executes the annotated method if the requested version is the same
   * with the previous lock version. Default value is false.
   */
  boolean executeOnSameVersion() default false;

  /**
   * Optional message used to wrap any exception thrown from inside the aspect as
   * {@code new IllegalStateException(failureMessage, cause)}. When empty (the default) the
   * original exception propagates unchanged. Setting this relieves callers from having to
   * declare or catch the checked {@link DbLockException} that may be raised by
   * {@code acquireLock}.
   */
  String failureMessage() default "";
}
