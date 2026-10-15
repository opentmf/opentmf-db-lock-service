package org.opentmf.db.lock.model;

import java.time.OffsetDateTime;
import lombok.Getter;
import lombok.ToString;

/**
 * The latest successfully performed lock's details.
 *
 * @author Gokhan Demir
 */
@Getter
@ToString
public class LatestLock {

  /**
   * The latest successfully performed lock's version.
   */
  private final String lockVersion;

  /**
   * The latest successfully performed lock was release at this date-time.
   */
  private final OffsetDateTime lockReleasedAt;

  /**
   * When this record was read, by the database's clock — the clock that stamped
   * {@link #lockReleasedAt}. {@code null} when it was read without it, in which case the JVM's
   * clock stands in when measuring how long ago the record was made.
   */
  private final OffsetDateTime readAt;

  public LatestLock(String lockVersion, OffsetDateTime lockReleasedAt) {
    this(lockVersion, lockReleasedAt, null);
  }

  public LatestLock(String lockVersion, OffsetDateTime lockReleasedAt, OffsetDateTime readAt) {
    this.lockVersion = lockVersion;
    this.lockReleasedAt = lockReleasedAt;
    this.readAt = readAt;
  }
}
