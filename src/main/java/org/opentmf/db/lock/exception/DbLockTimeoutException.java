package org.opentmf.db.lock.exception;

/**
 * @author Gokhan Demir
 */
public class DbLockTimeoutException extends DbLockException {

  public DbLockTimeoutException(String message) {
    super(message);
  }
}
