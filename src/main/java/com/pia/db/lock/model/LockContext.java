package com.pia.db.lock.model;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class LockContext {

  private LatestLock latestLock;
  private boolean upgrade;
  private String requestedVersion;
}
