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
   * <strong>true</strong>, if we are performing an upgrade, or <strong>false</strong> if
   * downgrade.
   */
  private boolean upgrade;

  /**
   * The resolved value of the <code>requestedVersion</code> parameter of
   * <code>@UsingClusterLock</code>.
   */
  private String requestedVersion;
}
