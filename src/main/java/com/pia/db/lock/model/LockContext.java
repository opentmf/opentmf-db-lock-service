package com.pia.db.lock.model;

import com.pia.db.lock.annotation.UsingClusterLock;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class LockContext {

  /**
   * The latest successfully performed lock details.
   *
   * @see LatestLock
   */
  private LatestLock latestLock;

  /**
   * <strong>true</strong>, if we are performing an upgrade, or <strong>false</strong> if
   * downgrade.
   *
   * @deprecated use {@link #versionChange} instead.
   */
  @Deprecated(since = "1.0.5", forRemoval = true)
  private boolean upgrade;

  /**
   * Represents the version transition of the acquired lock.
   *
   * <p>The possible values are:
   *
   * <ul>
   *   <li>{@link VersionChange#UPGRADE} - The requested version is higher than the lock version.
   *   <li>{@link VersionChange#RETAIN} - The requested version and the lock version are the same or
   *       requested version is lower than the lock version but it has not passed {@link
   *       UsingClusterLock#downgradeAllowedMillis()} milliseconds until the last synchronization.
   *   <li>{@link VersionChange#DOWNGRADE} - The requested version is lower than the lock version.
   * </ul>
   */
  private VersionChange versionChange;

  /**
   * The resolved value of the <code>requestedVersion</code> parameter of <code>@UsingClusterLock
   * </code>.
   */
  private String requestedVersion;
}
