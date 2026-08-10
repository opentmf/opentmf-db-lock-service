package org.opentmf.db.lock.dialect;

import java.util.Arrays;
import java.util.Locale;

/**
 * Supported SQL dialects for the bundled lock-table DDL scripts.
 *
 * <p>The value is either resolved from configuration ({@code opentmf.db-lock.dialect}) or
 * auto-detected from the JDBC connection metadata. Each entry points at a classpath-resident
 * creation script that satisfies the schema contract of the library (see {@code README.md}).
 *
 * @author Gokhan Demir
 */
public enum Dialect {

  POSTGRESQL("postgresql", "db/postgresql.sql", ";"),
  MYSQL("mysql", "db/mysql.sql", ";"),
  ORACLE("oracle", "db/oracle.sql", "/"),
  SQLSERVER("sqlserver", "db/sqlserver.sql", ";"),
  DB2("db2", "db/db2.sql", "/"),
  H2("h2", "db/h2.sql", ";");

  private final String id;
  private final String ddlResourcePath;
  private final String statementSeparator;

  Dialect(String id, String ddlResourcePath, String statementSeparator) {
    this.id = id;
    this.ddlResourcePath = ddlResourcePath;
    this.statementSeparator = statementSeparator;
  }

  /**
   * Returns the lower-case dialect identifier used in configuration (e.g. {@code postgresql}).
   */
  public String getId() {
    return id;
  }

  /**
   * Returns the classpath location of the creation script shipped for this dialect.
   */
  public String getDdlResourcePath() {
    return ddlResourcePath;
  }

  /**
   * Returns the statement separator used by the bundled DDL script. Defaults to {@code ";"};
   * Oracle and DB2 use {@code "/"} because their scripts contain anonymous PL/SQL / compound-SQL
   * blocks that embed literal semicolons.
   */
  public String getStatementSeparator() {
    return statementSeparator;
  }

  /**
   * Resolves an enum value from a case-insensitive identifier (e.g. {@code "postgresql"},
   * {@code "MySQL"}). Throws {@link IllegalArgumentException} with a descriptive message listing
   * the supported identifiers when the argument does not match any known dialect.
   */
  public static Dialect fromId(String id) {
    if (id != null) {
      String normalized = id.toLowerCase(Locale.ROOT);
      for (Dialect dialect : values()) {
        if (dialect.id.equals(normalized)) {
          return dialect;
        }
      }
    }
    throw new IllegalArgumentException(
        "Unknown dialect id: '" + id + "'. Supported ids: " + supportedIds());
  }

  private static String supportedIds() {
    return Arrays.toString(Arrays.stream(values()).map(Dialect::getId).toArray());
  }
}
