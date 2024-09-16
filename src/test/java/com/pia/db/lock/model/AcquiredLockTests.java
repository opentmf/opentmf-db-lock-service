package com.pia.db.lock.model;

import static org.awaitility.Awaitility.await;

import com.pia.db.lock.model.AcquiredLock;
import com.pia.db.lock.model.LockType;
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

  @Test
  void test_downgradeShouldBeAllowed_withinTheAllowedTime() {
    AcquiredLock lock = new AcquiredLock(1, LockType.BPMN, "2.0", OffsetDateTime.now());
    await()
        .atMost(3, TimeUnit.SECONDS)
        .pollInterval(300, TimeUnit.MILLISECONDS)
        .until(() -> downgradeAllowed(lock));
  }

  private static final AcquiredLock LOCK =
      new AcquiredLock(1, LockType.BPMN, "1.1", OffsetDateTime.now());

  @ParameterizedTest
  @ValueSource(strings = {"1.0", "1.1"})
  void test_upgradeShouldBePrevented_forSameOrOlderVersion(String requestedVersion) {
    Assertions.assertFalse(LOCK.isUpgradeRequired(requestedVersion));
  }

  private boolean downgradeAllowed(AcquiredLock lock) {
    return lock.isDowngradeRequired("1.0", TIMEOUT);
  }
}
