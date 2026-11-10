package org.opentmf.db.lock.exception;

/**
 * Thrown by {@code DbLockService.renewLock} when the lock is no longer the caller's to renew: it
 * was released, its lease expired, or another instance reclaimed it as stale. Work still running
 * under that lock is no longer protected by it, and another instance may already be doing the
 * same work; the caller should stop and, if the work must go on, acquire the lock again.
 *
 * @author Gokhan Demir
 */
public class DbLockLostException extends DbLockException {

  public DbLockLostException(String message) {
    super(message);
  }
}
