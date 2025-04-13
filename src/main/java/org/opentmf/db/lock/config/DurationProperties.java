package org.opentmf.db.lock.config;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import lombok.Getter;
import lombok.Setter;

/**
 * @author Gokhan Demir
 */
@Getter
@Setter
public class DurationProperties {

  /**
   * The interval in milliseconds to check if the requested type of lock already exists or not.
   * Minimum 100 milliseconds.
   */
  @Positive
  @Min(100L)
  long lockAcquirePollInterval = 1000L;

  /**
   * The maximum duration in milliseconds to wait until the requested type of lock is available.
   * Minimum 1 second, default 2 minutes.
   */
  @Positive
  @Min(1000L)
  long lockAcquireTimeout = 120000L;

  /**
   * The maximum duration in milliseconds, that an obtained lock can be hold. When this timeout is
   * reached, the lock will automatically be released. Minimum 2 seconds, default 5 minutes.
   */
  @Positive
  @Min(2000L)
  long lockHoldTimeout = 300000L;
}
