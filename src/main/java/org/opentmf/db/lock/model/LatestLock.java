package org.opentmf.db.lock.model;

import java.time.OffsetDateTime;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.ToString;

/**
 * The latest successfully performed lock's details.
 *
 * @author Gokhan Demir
 */
@RequiredArgsConstructor
@Getter
@ToString
public class LatestLock {

  /**
   * The latest successfully performed lock's version.
   */
  private final String lockVersion;

  /**
   * The latest successfully performed lock was release at this date-time.
   */
  private final OffsetDateTime lockReleasedAt;
}
