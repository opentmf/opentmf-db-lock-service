package com.pia.db.lock.model;

import static org.awaitility.Awaitility.await;

import java.time.OffsetDateTime;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * @author Gokhan Demir
 */
class AcquiredLockTests {

  private static final long TIMEOUT = 2000L;

  private static final LatestLock LATEST_LOCK = new LatestLock("1.1", OffsetDateTime.now());

  @Test
  void test_downgradeShouldBeAllowed_withinTheAllowedTime() {
    AcquiredLock lock = AcquiredLock.of(1, LockType.BPMN, "1.0", LATEST_LOCK);
    await()
        .atMost(3, TimeUnit.SECONDS)
        .pollInterval(300, TimeUnit.MILLISECONDS)
        .until(() -> downgradeIsAllowed(lock));
  }

  @ParameterizedTest
  @ValueSource(strings = {"1.0", "1.1"})
  void test_upgradeShouldBePrevented_forSameOrOlderVersion(String requestedVersion) {
    Assertions.assertFalse(acquiredLock(requestedVersion).isUpgrade());
  }

  private boolean downgradeIsAllowed(AcquiredLock lock) {
    return lock.isDowngrade() && lock.isDowngradeAllowed(TIMEOUT);
  }

  private AcquiredLock acquiredLock(String version) {
    return AcquiredLock.of(1, LockType.LOCK_X, version, LATEST_LOCK);
  }
}
