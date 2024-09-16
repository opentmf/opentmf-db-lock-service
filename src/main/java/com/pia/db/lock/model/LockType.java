package com.pia.db.lock.model;

import lombok.Getter;

/**
 * Specifies the type of the DB lock.
 *
 * @author Gokhan Demir
 */
@Getter
public enum LockType {

  /**
   * This lock type is used when synchronizing the BPMN data with Camunda.
   */
  BPMN("B"),

  /**
   * This lock type is used when synchronizing the product and resource catalog data.
   */
  CATALOG("C"),

  /**
   * General purpose lock type X.
   */
  LOCK_X("X"),

  /**
   * General purpose lock type Y.
   */
  LOCK_Y("Y"),

  /**
   * General purpose lock type Z.
   */
  LOCK_Z("Z");

  private final String dbValue;

  LockType(String dbValue) {
    this.dbValue = dbValue;
  }
}
