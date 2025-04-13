package org.opentmf.db.lock.service.api;

import org.opentmf.db.lock.exception.DbLockException;
import org.opentmf.db.lock.model.AcquiredLock;
import org.opentmf.db.lock.model.LockType;

/**
 * @author Gokhan Demir
 */
public interface DbLockService {

  /**
   * Acquires a persistent lock on the DB.
   *
   * @param lockType The lock type.
   * @param lockVersion The related lock type's version.
   * @return the acquired lock version and the latest completed lock details.
   * @throws DbLockException On something unexpected occurs.
   */
  AcquiredLock acquireLock(LockType lockType, String lockVersion) throws DbLockException;

  /**
   * Releases the previously acquired lock.
   *
   * @param lock The acquired lock details.
   * @param updateLatestLock true if the latest lock record needs to be updated.
   */
  void releaseLock(AcquiredLock lock, boolean updateLatestLock) throws DbLockException;
}
