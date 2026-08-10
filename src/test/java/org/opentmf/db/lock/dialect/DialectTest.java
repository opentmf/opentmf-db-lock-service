package org.opentmf.db.lock.dialect;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class DialectTest {

  @ParameterizedTest
  @EnumSource(Dialect.class)
  void fromId_roundTripsForEveryDialect(Dialect dialect) {
    assertSame(dialect, Dialect.fromId(dialect.getId()));
    assertSame(dialect, Dialect.fromId(dialect.getId().toUpperCase()));
  }

  @Test
  void fromId_throwsForUnknownId() {
    IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
        () -> Dialect.fromId("sybase"));
    assertTrue(ex.getMessage().contains("Unknown dialect id"));
    assertTrue(ex.getMessage().contains("postgresql"));
  }

  @Test
  void fromId_throwsForNull() {
    assertThrows(IllegalArgumentException.class, () -> Dialect.fromId(null));
  }

  @ParameterizedTest
  @EnumSource(Dialect.class)
  void ddlResourcePath_isSet(Dialect dialect) {
    assertNotNull(dialect.getDdlResourcePath());
    assertTrue(dialect.getDdlResourcePath().startsWith("db/"));
    assertTrue(dialect.getDdlResourcePath().endsWith(".sql"));
  }

  @Test
  void statementSeparator_isSlashForOracleAndDb2_semicolonForOthers() {
    assertEquals("/", Dialect.ORACLE.getStatementSeparator());
    assertEquals("/", Dialect.DB2.getStatementSeparator());
    assertEquals(";", Dialect.POSTGRESQL.getStatementSeparator());
    assertEquals(";", Dialect.MYSQL.getStatementSeparator());
    assertEquals(";", Dialect.SQLSERVER.getStatementSeparator());
    assertEquals(";", Dialect.H2.getStatementSeparator());
  }
}
