package org.opentmf.db.lock.dialect;

import java.util.Arrays;
import java.util.Locale;
import org.springframework.jdbc.datasource.init.ScriptUtils;

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

  // PostgreSQL DDL runs as a single batch (EOF separator) because postgresql.sql contains a
  // PL/pgSQL DO block whose body embeds semicolons; see getStatementSeparator() for details.
  POSTGRESQL("postgresql", "db/postgresql.sql", ScriptUtils.EOF_STATEMENT_SEPARATOR),
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
   * Returns the statement separator used by the bundled DDL script. Most dialects use {@code ";"}.
   * Oracle and DB2 use {@code "/"} because their scripts contain anonymous PL/SQL / compound-SQL
   * blocks that embed literal semicolons.
   *
   * <p>PostgreSQL uses {@link ScriptUtils#EOF_STATEMENT_SEPARATOR}, so its script is handed to the
   * driver as one batch rather than being split: it contains a PL/pgSQL {@code DO} block (the
   * legacy {@code lock_version} widening guard) whose body embeds semicolons. PostgreSQL executes
   * a multi-statement batch natively, so the script keeps its ordinary {@code ";"} punctuation
   * instead of being re-written around a {@code "/"} separator the way the Oracle and DB2 scripts
   * are. The trade-off is error reporting: a failure surfaces as one exception carrying the whole
   * script rather than naming the individual statement that failed.
   *
   * <p>Note for callers: this value is dialect-specific and not a general-purpose default. Code
   * splitting its own {@code ";"}-separated script should pass {@code ";"} explicitly rather than
   * borrow this. The user-supplied {@code ddl-location} path deliberately does exactly that.
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
