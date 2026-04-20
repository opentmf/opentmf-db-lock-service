package org.opentmf.db.lock.model;

import lombok.Getter;
import lombok.Setter;

/**
 * @author Abdullah Beker
 */
@Getter
@Setter
public class LockContext {

  /**
   * The resolved value of the <code>requestedVersion</code> parameter of <code>@UsingClusterLock
   * </code>.
   */
  private String requestedVersion;

  /**
   * The latest successfully performed lock details. Can be NULL if no previous lock exists.
   */
  private LatestLock latestLock;

  /**
   * Represents the version transition between the previous lock version and the requested version.
   */
  private VersionTransition versionTransition;

  /**
   * Indicates whether the annotated method considers its execution successful. Defaults to
   * <b>true</b>. When the business logic completes normally but determines that no effective
   * change was applied (e.g. all target resources were already up-to-date), it may call
   * {@code setSuccess(false)} to signal that the lock must be released <em>without</em>
   * recording the requested version as the latest completed version. On the next run with the
   * same version, the method will be re-executed instead of being short-circuited.
   *
   * <p>Ignored when the annotated method does not declare a {@link LockContext} parameter or
   * when it throws an exception (which always releases the lock with success=false).
   */
  private boolean success = true;

  /**
   * Returns true if this is the initial lock that we have acquired, false otherwise.
   * @return true if this is the initial lock that we have acquired, false otherwise.
   */
  public boolean isInitial() {
    return latestLock == null;
  }

  /**
   * Returns true is this is an upgrade.
   * <p>
   * An upgrade means, either there is no successfully executed previous lock, or the current
   * requested lock version is greater than the previous successful lock.
   * </p>
   *
   * @return true is this is an upgrade, false otherwise.
   */
  public boolean isUpgrade() {
    return versionTransition == VersionTransition.UPGRADE;
  }

  /**
   * Returns true is this is a downgrade and this downgrade is allowed to be executed.
   * <p>
   * A downgrade which means the requested version is saller than the latest successfully completed
   * version and the downgradeAllowedAfter has been reached. So we must be going for a downgrade.
   * </p>
   *
   * @return Returns true is this is a downgrade and this downgrade is allowed to be executed, false
   * otherwise.
   */
  public boolean isDowngrade() {
    return versionTransition == VersionTransition.DOWNGRADE;
  }

  /**
   * Returns true if the requested and obtained lock version is the same as the latest successfully
   * executed lock version. There can be cases where a business logic must be executed each time a
   * lock is obtained, even though it is the same as the latest lock version.
   *
   * @return true if the requested and obtained lock version is the same as the latest successfully
   * * executed lock version, false otherwise.
   */
  public boolean isSameVersion() {
    return versionTransition == VersionTransition.SAME_VERSION;
  }
}
