package org.opentmf.db.lock.util;

import org.opentmf.db.lock.config.DbLockProperties;
import org.opentmf.db.lock.model.LockType;
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
