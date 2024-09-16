package com.pia.db.lock.model;

import java.time.OffsetDateTime;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * @author Gokhan Demir
 */
@RequiredArgsConstructor
@Getter
public class LatestLock {

  private final String lockVersion;
  private final OffsetDateTime lockReleasedAt;
}
