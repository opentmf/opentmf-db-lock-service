package com.pia.db.lock.util;

import com.pia.db.lock.config.DbLockProperties;
import com.pia.db.lock.model.LockType;
import lombok.RequiredArgsConstructor;

/**
 * @author Gokhan Demir
 */
@RequiredArgsConstructor
public class DurationHelper {

  private final DbLockProperties lockProperties;

  public long getLockAcquirePollInterval(LockType lockType) {
    return lockProperties.getDurationOverrides().containsKey(lockType)
        ? lockProperties.getDurationOverrides().get(lockType).getLockAcquirePollInterval()
        : lockProperties.getLockAcquirePollInterval();
  }

  public long getLockAcquireTimeout(LockType lockType) {
    return lockProperties.getDurationOverrides().containsKey(lockType)
        ? lockProperties.getDurationOverrides().get(lockType).getLockAcquireTimeout()
        : lockProperties.getLockAcquireTimeout();
  }

  public long getLockHoldTimeout(LockType lockType) {
    return lockProperties.getDurationOverrides().containsKey(lockType)
        ? lockProperties.getDurationOverrides().get(lockType).getLockHoldTimeout()
        : lockProperties.getLockHoldTimeout();
  }



}
