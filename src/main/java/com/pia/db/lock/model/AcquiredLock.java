package com.pia.db.lock.model;

import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.ToString;
import org.springframework.lang.NonNull;
import org.springframework.util.Assert;

/**
 * This class holds the acquired lock's lockId and the lock type, the latest accomplished lock's
 * version and the release datetime, together with two helper methods for easing the upgrade and
 * downgrade required decisions.
 *
 * @author Gokhan Demir
 */
@RequiredArgsConstructor
@Getter
@ToString
@EqualsAndHashCode(of = {"lockId"})
public final class AcquiredLock {

  private final int lockId;
  private final LockType lockType;
  private final String previousLockVersion;
  private final OffsetDateTime previousLockReleasedAt;

  private final OffsetDateTime lockAcquiredAt = OffsetDateTime.now();

  /**
   * Returns true if an upgrade is required, which means either there is no previous
   * synchronization or the previous synchronization version is lower than the requested
   * version using string comparison.
   * @param requestedVersion the requested version.
   * @return true if an upgrade is required, false otherwise.
   *
   * @deprecated Use {@link #getVersionChange} instead.
   */
  @Deprecated(since = "1.0.5", forRemoval = true)
  public boolean isUpgradeRequired(@NonNull String requestedVersion) {
    return previousLockVersion == null || previousLockVersion.compareTo(requestedVersion) < 0;
  }

  /**
   * Compares the requested version with the previous lock version and returns the version change.
   *
   * @param requestedVersion The version requested to be synchronized to.
   * @return {@link VersionChange#UPGRADE} if there is no previous lock version or the requested
   *     version is higher than the previous lock version, {@link VersionChange#NO_CHANGE} if the
   *     requested version is the same as the previous lock version, {@link VersionChange#DOWNGRADE}
   *     if the requested version is lower than the previous lock version.
   */
  public VersionChange getVersionChange(@NonNull String requestedVersion) {
    if (previousLockVersion == null || previousLockVersion.compareTo(requestedVersion) < 0) {
      return VersionChange.UPGRADE;
    } else if (previousLockVersion.equals(requestedVersion)) {
      return VersionChange.NO_CHANGE;
    } else {
      return VersionChange.DOWNGRADE;
    }
  }

  /**
   * Checks if a downgrade is allowed.
   *
   * @param downgradeAllowedAfter the duration in milliseconds which must have passed after the last
   *     synchronization.
   * @return <b>true</b> if a downgrade is allowed, meaning the previous lock was released at least
   *     <b>downgradeAllowedAfter</b> milliseconds ago, otherwise <b>false</b>.
   */
  public boolean isDowngradeAllowed(long downgradeAllowedAfter) {
    return getPreviousLockReleasedAt()
        .isBefore(OffsetDateTime.now().minus(downgradeAllowedAfter, ChronoUnit.MILLIS));
  }

  /**
   * Returns true is a downgrade is required, which means
   * @param requestedVersion the requested version.
   * @param downgradeAllowedAfter a duration in terms of milliseconds which must have passed
   *                              after the last synchronization for the downgrade to be allowed
   *                              even though the requested version is lower than the latest
   *                              synchronized version.
   * @return true is a downgrade is required, false otherwise.
   *
   * @deprecated Use the combination of {@link #getVersionChange} and {@link #isDowngradeAllowed} instead.
   */
  @Deprecated(since = "1.0.5", forRemoval = true)
  public boolean isDowngradeRequired(@NonNull String requestedVersion, long downgradeAllowedAfter) {
    Assert.notNull(previousLockVersion,
        "Previous lock version must not be null when calculating whether downgrade is required.");
    Assert.notNull(previousLockReleasedAt,
        "Previous lock releasedAt must not be null when calculating whether downgrade is required.");
    return previousLockVersion.compareTo(requestedVersion) > 0 &&
        getPreviousLockReleasedAt()
            .isBefore(OffsetDateTime.now()
                .minus(downgradeAllowedAfter, ChronoUnit.MILLIS));
  }
}
