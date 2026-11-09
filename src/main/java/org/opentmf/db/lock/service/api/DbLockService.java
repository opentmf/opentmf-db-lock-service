package org.opentmf.db.lock.service.api;

import org.opentmf.db.lock.exception.DbLockException;
import org.opentmf.db.lock.exception.DbLockLostException;
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

  /**
   * Renews the lease of a lock this caller holds: its hold-timeout starts over, measured from now
   * by the database's clock. A holder whose work can outlast the hold-timeout calls this at regular
   * intervals (a third of the hold-timeout leaves room for two missed beats), so that it keeps the
   * lock while other instances still reclaim it promptly once the holder has crashed or hung.
   *
   * <p>The contract:
   * <ul>
   *   <li>Only the holder can renew: the service instance that acquired the lock, while it still
   *       holds it. The lock is identified by its id ({@link AcquiredLock#getLockId()}), which is
   *       unique to one acquisition; a later lock of the same type has another id and is never
   *       renewed by this call.</li>
   *   <li>A renewed lock is judged fresh by every instance: its age is measured from the renewal,
   *       both by a waiting instance's stale-lock reclaim and by the startup sweep, and the holding
   *       instance's own hold-timeout is re-armed for the full period.</li>
   *   <li>A renewal and a reclaim of the same lock never both succeed: each decides under the
   *       lock row's write lock, so a reclaim that starts while a renewal is in flight sees the
   *       renewed lease.</li>
   *   <li>A lock whose lease has already expired is not renewed. If it is still in the database
   *       it is released at once, as its hold-timeout would have done, and the call throws.</li>
   * </ul>
   *
   * <p>The lock's id, type and version are unchanged by a renewal. {@code DB_LOCK.created_on} is
   * set to the time of the renewal, so the {@code lock_acquired_on} later copied from it into
   * {@code DB_LOCK_HISTORY} and {@code DB_LOCK_LATEST} is the start of the lock's last lease.
   *
   * @param lock The acquired lock details, as returned by {@link #acquireLock(LockType, String)}.
   * @throws DbLockLostException when the lock is no longer held by the caller — released, expired,
   *     reclaimed by another instance, or never acquired by this one. The work it guarded is no
   *     longer protected.
   * @throws DbLockException when the database could not be reached; the lease is then unchanged,
   *     and so is this instance's hold-timeout for it.
   * @since 3.1.0
   */
  void renewLock(AcquiredLock lock) throws DbLockException;

    /**
     * Checks if there is an ongoing lock of the given type, returns true if.
     * @param lockType the requested lock type.
     * @return true if there is an ongoing lock for this lock type, false otherwise.
     */
  boolean hasLock(LockType lockType);
}
