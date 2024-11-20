package com.pia.db.lock.model;

/**
 * Represents a transition between two versions.
 *
 * <p>The possible values are:
 *
 * <ul>
 *   <li>{@link VersionChange#UPGRADE} - The later version is higher than the previous version.
 *   <li>{@link VersionChange#NO_CHANGE} - The later version and the previous version are the same.
 *   <li>{@link VersionChange#DOWNGRADE} - The later version is lower than the previous version.
 * </ul>
 *
 * @author Abdullah Beker
 */
public enum VersionChange {
  DOWNGRADE,
  UPGRADE,
  NO_CHANGE,
}
