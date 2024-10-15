package com.pia.db.lock.service;

import com.pia.db.lock.config.TestProperties;
import com.pia.db.lock.exception.DbLockException;
import com.pia.db.lock.model.LockContext;
import com.pia.db.lock.model.LockType;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.lang.reflect.UndeclaredThrowableException;
import java.time.OffsetDateTime;

@SpringBootTest
@ActiveProfiles("it")
@EnableConfigurationProperties(TestProperties.class)
class AnnotatedTestServiceIT {

  @Autowired private TestProperties testProperties;
  @Autowired private AnnotatedTestService annotatedTestService;
  @Autowired private AnnotatedTestPersistenceService annotatedTestPersistenceService;

  @Test
  void testServiceUsingClusterLock_withPersistHistoryOnSuccess_persistHistory() {
    annotatedTestPersistenceService.deleteLocks(LockType.LOCK_X);
    var initialCount = annotatedTestPersistenceService.historyCount(LockType.LOCK_X);
    Assertions.assertEquals(0, initialCount);
    annotatedTestService.taskOne();
    Assertions.assertEquals(
        initialCount + 1, annotatedTestPersistenceService.historyCount(LockType.LOCK_X));
  }

  @Test
  void testServiceUsingClusterLock_withoutPersistLatestOnException_doesNotPersistLatest() {
    annotatedTestPersistenceService.deleteLocks(LockType.LOCK_Y);
    var initialCount = annotatedTestPersistenceService.historyCount(LockType.LOCK_Y);
    Assertions.assertEquals(0, initialCount);
    var e =
        Assertions.assertThrows(IllegalStateException.class, () -> annotatedTestService.taskTwo());
    Assertions.assertEquals("taskTwo is failed", e.getMessage());
    Assertions.assertEquals(
        initialCount + 1, annotatedTestPersistenceService.historyCount(LockType.LOCK_Y));
    Assertions.assertFalse(annotatedTestPersistenceService.latestLockExists(LockType.LOCK_Y));
  }

  @Test
  public void testServiceUsingClusterLock_withProperty_resolveProperty() {
    annotatedTestPersistenceService.deleteLocks(LockType.LOCK_Z);
    annotatedTestService.lockWithPropertyValue();
    String latestLockVersion =
        annotatedTestPersistenceService.getLatestLockVersion(LockType.LOCK_Z);
    Assertions.assertEquals(latestLockVersion, testProperties.getVersion1());
  }

  @Test
  public void testServiceUsingClusterLock_withExpression_resolveExpression() {
    annotatedTestPersistenceService.deleteLocks(LockType.LOCK_Z);
    annotatedTestService.taskWithExpression();
    String latestLockVersion =
        annotatedTestPersistenceService.getLatestLockVersion(LockType.LOCK_Z);
    Assertions.assertEquals(latestLockVersion, "2.0");
  }

  @Test
  public void
      testServiceUsingClusterLock_withAlreadyAcquiredLock_doesNotAcquireLockDoNotUpdateHistory() {
    annotatedTestPersistenceService.deleteLocks(LockType.LOCK_Z);
    annotatedTestPersistenceService.insertLock(LockType.LOCK_Z, "3.0");
    var initialCount = annotatedTestPersistenceService.historyCount(LockType.LOCK_Z);
    Exception exception =
        Assertions.assertThrows(Exception.class, () -> annotatedTestService.taskThree());
    Assertions.assertInstanceOf(UndeclaredThrowableException.class, exception);
    Assertions.assertInstanceOf(
        DbLockException.class, ((UndeclaredThrowableException) exception).getUndeclaredThrowable());
    Assertions.assertEquals(
        initialCount, annotatedTestPersistenceService.historyCount(LockType.LOCK_Z));
  }

  @Test
  public void
      testServiceUsingClusterLock_withDowngradeRequired_downgradeLatestVersionSaveHistory() {
    annotatedTestPersistenceService.deleteLocks(LockType.LOCK_Z);
    var initialCount = annotatedTestPersistenceService.historyCount(LockType.LOCK_Z);
    annotatedTestPersistenceService.insertLockLatest(
        LockType.LOCK_Z, "3.0", OffsetDateTime.now().minusMinutes(11));
    annotatedTestService.taskWithExpression();
    String latestLockVersion =
        annotatedTestPersistenceService.getLatestLockVersion(LockType.LOCK_Z);
    Assertions.assertEquals("2.0", latestLockVersion);
    Assertions.assertEquals(
        initialCount + 1, annotatedTestPersistenceService.historyCount(LockType.LOCK_Z));
  }

  @Test
  public void testServiceUsingClusterLock_withNoUpgradeRequired_doNothingReturnNull() {
    annotatedTestPersistenceService.deleteLocks(LockType.LOCK_Z);
    var initialCount = annotatedTestPersistenceService.historyCount(LockType.LOCK_Z);
    annotatedTestPersistenceService.insertLockLatest(LockType.LOCK_Z, "3.0", OffsetDateTime.now());
    String s = annotatedTestService.taskThree();
    Assertions.assertNull(s);
    Assertions.assertEquals(
        initialCount + 1, annotatedTestPersistenceService.historyCount(LockType.LOCK_Z));
  }

  @Test
  public void testServiceUsingClusterLock_withCorruptedPropertyFormat_doesNothingThrowsException() {
    annotatedTestPersistenceService.deleteLocks(LockType.LOCK_Z);
    Exception exception =
        Assertions.assertThrows(
            Exception.class, () -> annotatedTestService.taskWithCorruptedPropertyFormat());
    Assertions.assertInstanceOf(UndeclaredThrowableException.class, exception);
    Assertions.assertInstanceOf(
        DbLockException.class, ((UndeclaredThrowableException) exception).getUndeclaredThrowable());
  }

  @Test
  public void testServiceUsingClusterLock_withCorruptedExpression_doesNothingThrowsException() {
    annotatedTestPersistenceService.deleteLocks(LockType.LOCK_Z);
    Exception exception =
        Assertions.assertThrows(
            Exception.class, () -> annotatedTestService.taskWithCorruptedExpressionFormat());
    Assertions.assertInstanceOf(UndeclaredThrowableException.class, exception);
    Assertions.assertInstanceOf(
        DbLockException.class, ((UndeclaredThrowableException) exception).getUndeclaredThrowable());
  }

  @Test
  public void testServiceUsingClusterLock_withCustomArg_passCustomArgToActualMethod() {
    annotatedTestPersistenceService.deleteLocks(LockType.LOCK_Y);
    String arg = "lock_y";
    String returnValue = this.annotatedTestService.taskWithCustomArg(arg);
    Assertions.assertEquals(returnValue, arg);
  }

  @Test
  public void
      testServiceUsingClusterLock_withCustomArgAndContextArg_passCustomArgAndContextArgToActualMethod() {
    annotatedTestPersistenceService.deleteLocks(LockType.LOCK_Y);
    annotatedTestPersistenceService.insertLockLatest(LockType.LOCK_Y, "4.0", OffsetDateTime.now());
    String arg = "lock_y";
    String returnValue =
        this.annotatedTestService.taskWithCustomArgAndContextArg(arg, new LockContext());
    Assertions.assertEquals(returnValue, "4.0" + arg);
  }

  @Test
  public void testServiceUsingClusterLock_withContextArg_passContextArgToActualMethod() {
    annotatedTestPersistenceService.deleteLocks(LockType.LOCK_Y);
    annotatedTestPersistenceService.insertLockLatest(LockType.LOCK_Y, "4.0", OffsetDateTime.now());
    LockContext context = this.annotatedTestService.taskWithContextArg(new LockContext());
    Assertions.assertNotNull(context);
    Assertions.assertEquals(context.getLatestLock().getLockVersion(), "4.0");
  }

  @Test
  public void
      testServiceUsingClusterLock_withNullContextArgAndNoPreviousLock_passContextArgToActualMethod() {
    annotatedTestPersistenceService.deleteLocks(LockType.LOCK_Y);
    LockContext context = this.annotatedTestService.taskWithContextArg(null);
    Assertions.assertNotNull(context);
    Assertions.assertNull(context.getLatestLock());
  }
}
