package org.opentmf.db.lock.model;

import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
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

  private static final Map<String, LockType> BY_DB_VALUE =
      Stream.of(values()).collect(Collectors.toMap(LockType::getDbValue, Function.identity()));

  private final String dbValue;

  LockType(String dbValue) {
    this.dbValue = dbValue;
  }

  /**
   * Returns the {@link LockType} for the given database value, or {@code null} if not found.
   *
   * @param dbValue the single-character database value (e.g. "B", "C", "X").
   * @return the matching LockType, or null.
   */
  public static LockType fromDbValue(String dbValue) {
    return BY_DB_VALUE.get(dbValue);
  }
}
