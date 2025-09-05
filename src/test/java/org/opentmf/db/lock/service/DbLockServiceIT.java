package org.opentmf.db.lock.service;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.opentmf.db.lock.exception.DbLockException;
import org.opentmf.db.lock.exception.DbLockTimeoutException;
import org.opentmf.db.lock.model.AcquiredLock;
import org.opentmf.db.lock.model.LockType;
import org.opentmf.db.lock.service.api.DbLockService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.annotation.DirtiesContext.MethodMode;
import org.springframework.test.context.junit.jupiter.SpringExtension;

/**
 * @author Gokhan Demir
 */
@SpringBootTest(webEnvironment = WebEnvironment.NONE)
@Slf4j
@ExtendWith(SpringExtension.class)
class DbLockServiceIT {

  @Autowired
  private DbLockService dbLockService;

  @Test
  void testAcquireLock_andThenReleaseTheLockOnTime_isSuccessful() throws DbLockException {
    AcquiredLock lock = dbLockService.acquireLock(LockType.BPMN, "1.0");
    Assertions.assertNotNull(lock);
    dbLockService.releaseLock(lock, true);
  }

  @Test
  void testAcquireLock_withoutReleasingFirst_timesOut() throws DbLockException {
    Assertions.assertFalse(dbLockService.hasLock(LockType.BPMN));
    AcquiredLock lock = dbLockService.acquireLock(LockType.BPMN, "1.0");
    DbLockTimeoutException e = assertThrows(DbLockTimeoutException.class,
        () -> dbLockService.acquireLock(LockType.BPMN, "1.0"));
    Assertions.assertEquals(
        "Cannot acquire DB Lock for BPMN within the configured 1000 millis. Giving up.",
        e.getMessage());
    dbLockService.releaseLock(lock, true);
  }

  @ParameterizedTest
  @EnumSource(LockType.class)
  void testAcquiredLock_notReleasedWithinAllowedTime_throwsError(LockType lockType)
      throws DbLockException {
    AcquiredLock acquiredLock = dbLockService.acquireLock(lockType, "1.0");
    Assertions.assertNotNull(acquiredLock);
    log.debug("Acquired lock details: {}", acquiredLock);
    Assertions.assertTrue(dbLockService.hasLock(lockType));
    await()
        .atMost(5, TimeUnit.SECONDS)
        .pollInterval(300, TimeUnit.MILLISECONDS)
        .until(() -> lockDoesNotExist(lockType));
    Assertions.assertTrue(lockDoesNotExist(lockType));
    dbLockService.releaseLock(acquiredLock, true);
  }

  @ParameterizedTest
  @EnumSource(LockType.class)
  @DirtiesContext(methodMode = MethodMode.AFTER_METHOD)
  void testAcquireLock_notReleasedBeforeShutdown_releasedInShutdownHook(LockType lockType)
      throws DbLockException {
    AcquiredLock acquiredLock = dbLockService.acquireLock(lockType, "1.0");
    Assertions.assertNotNull(acquiredLock);
    log.debug("Acquired lock details: {}", acquiredLock);
    await().atMost(1, TimeUnit.SECONDS);
    Assertions.assertFalse(lockDoesNotExist(lockType));
  }

  private boolean lockDoesNotExist(LockType lockType) {
    return !dbLockService.hasLock(lockType);
  }
}
