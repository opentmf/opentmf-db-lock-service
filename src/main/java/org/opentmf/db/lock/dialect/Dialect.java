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

  POSTGRESQL("postgresql", "db/postgresql.sql", ";", "CURRENT_TIMESTAMP"),
  MYSQL("mysql", "db/mysql.sql", ";", "CURRENT_TIMESTAMP"),
  ORACLE("oracle", "db/oracle.sql", "/", "CURRENT_TIMESTAMP"),
  SQLSERVER("sqlserver", "db/sqlserver.sql", ";", "SYSDATETIMEOFFSET()"),
  DB2("db2", "db/db2.sql", "/", "CURRENT TIMESTAMP"),
  H2("h2", "db/h2.sql", ";", "CURRENT_TIMESTAMP");

  private final String id;
  private final String ddlResourcePath;
  private final String statementSeparator;
  private final String currentTimestampSql;

  Dialect(String id, String ddlResourcePath, String statementSeparator,
      String currentTimestampSql) {
    this.id = id;
    this.ddlResourcePath = ddlResourcePath;
    this.statementSeparator = statementSeparator;
    this.currentTimestampSql = currentTimestampSql;
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
   * Returns the SQL expression for the database's current time, of the same type and from the
   * same clock as the {@code created_on} default in the bundled DDL. The age of a lock is measured
   * against it rather than the JVM's clock: that keeps clock skew out, and with it the time-zone
   * misreading a plain {@code TIMESTAMP} column (MySQL, DB2) suffers when the driver's assumed time
   * zone is not the server's, since both values are read back the same way.
   */
  public String getCurrentTimestampSql() {
    return currentTimestampSql;
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
