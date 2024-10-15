package com.pia.db.lock.service;

import com.pia.db.lock.annotation.UsingClusterLock;
import com.pia.db.lock.model.LockContext;
import com.pia.db.lock.model.LockType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * @author abdullahbeker
 */
@Slf4j
@Service
public class AnnotatedTestService {

  @UsingClusterLock(lockType = LockType.LOCK_X, requestedVersion = "1")
  public void taskOne() {
    log.debug("taskOne is running");
  }

  @UsingClusterLock(lockType = LockType.LOCK_Y, requestedVersion = "1")
  public void taskTwo() {
    throw new IllegalStateException("taskTwo is failed");
  }

  @UsingClusterLock(lockType = LockType.LOCK_Z, requestedVersion = "${test.properties.version1}")
  public void lockWithPropertyValue() {
    log.debug("lock with property value is running");
  }

  @UsingClusterLock(lockType = LockType.LOCK_Z, requestedVersion = "#{1 + 1 + '.0'}")
  public void taskWithExpression() {
    log.debug("lock with expression is running");
  }

  @UsingClusterLock(lockType = LockType.LOCK_Z, requestedVersion = "#{2 + 1 + '.0'}")
  public String taskThree() {
    log.debug("task three is running");
    return "3.0";
  }

  @UsingClusterLock(lockType = LockType.LOCK_Z, requestedVersion = "${2 + 1 + '.0'")
  public void taskWithCorruptedPropertyFormat() {
    log.debug("task with corrupted property format is running");
  }

  @UsingClusterLock(lockType = LockType.LOCK_Z, requestedVersion = "#{2 + 1 + '.0'")
  public void taskWithCorruptedExpressionFormat() {
    log.debug("task with corrupted expression format is running");
  }

  @UsingClusterLock(lockType = LockType.LOCK_Y, requestedVersion = "4.0")
  public String taskWithCustomArg(String arg1) {
    return arg1;
  }

  @UsingClusterLock(lockType = LockType.LOCK_Y, requestedVersion = "5.0")
  public String taskWithCustomArgAndContextArg(String arg1, LockContext context) {
    return context.getLatestLock().getLockVersion() + arg1;
  }

  @UsingClusterLock(lockType = LockType.LOCK_Y, requestedVersion = "5.0")
  public LockContext taskWithContextArg(LockContext context) {
    return context;
  }
}
