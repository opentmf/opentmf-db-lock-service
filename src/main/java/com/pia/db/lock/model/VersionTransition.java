package com.pia.db.lock.model;

/**
 * Represents a transition between two versions.
 *
 * @author Abdullah Beker
 */
public enum VersionTransition {

  /**
   * New version is greater than the previous version.
   */
  UPGRADE,

  /**
   * New and previous versions are equal.
   */
  NO_CHANGE,

  /**
   * New version is smaller than the previous version.
   */
  DOWNGRADE
}
