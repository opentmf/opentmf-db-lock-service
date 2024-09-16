package com.pia.db.lock.exception;

/**
 * @author Gokhan Demir
 */
public class DbLockException extends Exception {

  public DbLockException(String message) {
    super(message);
  }

  public DbLockException(String message, Throwable cause) {
    super(message, cause);
  }
}
