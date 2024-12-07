package com.pia.db.lock.model;

import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import lombok.AccessLevel;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.ToString;

/**
 * This class holds the acquired lock's lockId and the lock type, the latest accomplished lock's
 * version and the release datetime, together with two helper methods for easing the upgrade and
 * downgrade required decisions.
 *
 * @author Gokhan Demir
 */
@RequiredArgsConstructor(access = AccessLevel.PRIVATE)
@Getter
@ToString
@EqualsAndHashCode(of = {"lockId"})
public final class AcquiredLock {

  private final int lockId;
  private final LockType lockType;
  private final String lockVersion;
  private final LatestLock previousLock;
  private VersionTransition versionTransition;

  private final OffsetDateTime lockAcquiredAt = OffsetDateTime.now();

  public static AcquiredLock of(int lockId, LockType lockType, String lockVersion,
      LatestLock previousLock) {
    var acquiredLock = new AcquiredLock(lockId, lockType, lockVersion, previousLock);
    acquiredLock.versionTransition = detectVersionTransition(acquiredLock);
    return acquiredLock;
  }

  private static VersionTransition detectVersionTransition(AcquiredLock lock) {
    if (lock.getPreviousLock() == null ||
        lock.previousLock.getLockVersion().compareTo(lock.getLockVersion()) < 0) {
      return VersionTransition.UPGRADE;
    } else if (lock.previousLock.getLockVersion().equals(lock.getLockVersion())) {
      return VersionTransition.SAME_VERSION;
    }
    return VersionTransition.DOWNGRADE;
  }

  public boolean isUpgrade() {
    return versionTransition == VersionTransition.UPGRADE;
  }

  public boolean isDowngrade() {
    return versionTransition == VersionTransition.DOWNGRADE;
  }

  public boolean isSameVersion() {
    return versionTransition == VersionTransition.SAME_VERSION;
  }

  /**
   * Checks if a downgrade is allowed.
   *
   * @param downgradeAllowedAfter the duration in milliseconds which must have passed after the last
   *                              synchronization.
   * @return <b>true</b> if a downgrade is allowed, meaning the previous lock was released at least
   * <b>downgradeAllowedAfter</b> milliseconds ago, otherwise <b>false</b>.
   */
  public boolean isDowngradeAllowed(long downgradeAllowedAfter) {
    return getPreviousLock().getLockReleasedAt().isBefore(
        OffsetDateTime.now().minus(downgradeAllowedAfter, ChronoUnit.MILLIS));
  }
}
