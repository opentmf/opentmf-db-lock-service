package com.pia.db.lock.model;

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
   * <strong>true</strong>, if we are performing an upgrade, or <strong>false</strong> if a
   * downgrade.
   *
   * @deprecated use {@link #versionChange} instead.
   */
  @Deprecated(since = "1.0.5", forRemoval = true)
  private boolean upgrade;

  /**
   * Represents the version change between the previous lock version and the requested version.
   *
   * @see AcquiredLock#getVersionChange
   */
  private VersionChange versionChange;

  /**
   * The resolved value of the <code>requestedVersion</code> parameter of <code>@UsingClusterLock
   * </code>.
   */
  private String requestedVersion;
}
