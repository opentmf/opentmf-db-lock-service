package org.opentmf.db.lock.util;

import org.opentmf.db.lock.dialect.Dialect;
import org.opentmf.db.lock.dialect.LockVersionMigration;
import org.opentmf.db.lock.model.LatestLock;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.Objects;
import javax.sql.DataSource;
import lombok.Generated;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.jspecify.annotations.NonNull;
import org.springframework.util.Assert;

/**
 * @author Gokhan Demir
 */
@Slf4j
public final class JdbcHelper {
  
  @Generated
  private JdbcHelper() {
  }

  public static int count(Connection conn, String sql, @NonNull String param) throws SQLException {
    try (PreparedStatement ps = conn.prepareStatement(sql)) {
      ps.setString(1, param);
      logSql(ps);
      ResultSet rs = ps.executeQuery();
      if (!rs.next()) {
        throw new SQLException("Count query returns no result.");
      }
      return rs.getInt(1);
    }
  }

  public static int executeUpdate(Connection conn, String sql, int param) throws SQLException {
    try (PreparedStatement ps = conn.prepareStatement(sql)) {
      ps.setInt(1, param);
      logSql(ps);
      return ps.executeUpdate();
    }
  }

  public static int executeUpdate(Connection conn, String sql, int param, String s) throws SQLException {
    try (PreparedStatement ps = conn.prepareStatement(sql)) {
      ps.setInt(1, param);
      ps.setString(2, s);
      logSql(ps);
      return ps.executeUpdate();
    }
  }

  public static int autoIncrementInsert(Connection conn, String sql, String... param)
      throws SQLException {
    // Most drivers return the auto-increment value via Statement.RETURN_GENERATED_KEYS, but
    // Oracle returns the ROWID unless the caller names the column(s) to return. Naming the
    // "id" column works across all supported dialects — we just have to pick the correct
    // identifier case (Oracle / DB2 / H2 fold unquoted names to upper case).
    String[] idColumn = { conn.getMetaData().storesUpperCaseIdentifiers() ? "ID" : "id" };
    try (PreparedStatement ps = conn.prepareStatement(sql, idColumn)) {
      for (int i = 0, n = param.length; i < n; i++) {
        ps.setString(i + 1, param[i]);
      }
      logSql(ps);
      int rowsAffected = ps.executeUpdate();
      if (rowsAffected != 1) {
        throw new SQLException("Insert affected " + rowsAffected + " rows.");
      }
      try (ResultSet rs = ps.getGeneratedKeys()) {
        Assert.isTrue(rs.next(), "Insert statement did not produce a generated key.");
        return rs.getInt(1);
      }
    }
  }

  public static void rollback(Connection conn) {
    if (conn != null) {
      try {
        conn.rollback();
      } catch (SQLException e) {
        log.warn("Ignoring exception during rollback.", e);
      }
    }
  }

  public static void commit(Connection conn) throws SQLException {
    conn.commit();
  }

  public static void close(Connection conn) {
    if (conn != null) {
      try {
        conn.close();
      } catch (SQLException e) {
        log.warn("Ignoring exception during connection close.", e);
      }
    }
  }

  public static LatestLock getLatestLock(Connection conn, String sql, String param)
      throws SQLException {
    try (PreparedStatement ps = conn.prepareStatement(sql)) {
      ps.setString(1, param);
      logSql(ps);
      ResultSet rs = ps.executeQuery();
      return rs.next()
          ? new LatestLock(rs.getString(1), rs.getObject(2, OffsetDateTime.class))
          : null;
    }
  }

  /**
   * Executes the DDL script shipped for the given {@link Dialect}, honoring its configured
   * statement separator.
   */
  public static void createTables(JdbcTemplate jdbcTemplate, Dialect dialect) {
    executeDdlScript(
        jdbcTemplate,
        new ClassPathResource(dialect.getDdlResourcePath()),
        dialect.getStatementSeparator());
    LockVersionMigration.widenLockVersion(jdbcTemplate, dialect);
  }

  /**
   * Executes an arbitrary DDL script against the data source backing {@code jdbcTemplate} using
   * the default {@code ";"} statement separator. Caller is responsible for ensuring the script
   * is idempotent when {@code createTables} is enabled across restarts.
   */
  public static void createTables(JdbcTemplate jdbcTemplate, Resource ddlScript) {
    executeDdlScript(jdbcTemplate, ddlScript, ScriptUtils.DEFAULT_STATEMENT_SEPARATOR);
  }

  private static void executeDdlScript(
      JdbcTemplate jdbcTemplate, Resource ddlScript, String separator) {
    log.debug("In dbLockService.createTables() with script = {} (separator = '{}')",
        ddlScript, separator);
    DataSource dataSource = jdbcTemplate.getDataSource();
    Assert.notNull(dataSource, "DataSource cannot be obtained during DB_Lock service init");
    try (Connection conn = dataSource.getConnection()) {
      logMetaData(conn.getMetaData(), conn.getSchema());
      ScriptUtils.executeSqlScript(
          conn,
          new EncodedResource(ddlScript),
          false,
          false,
          ScriptUtils.DEFAULT_COMMENT_PREFIX,
          separator,
          ScriptUtils.DEFAULT_BLOCK_COMMENT_START_DELIMITER,
          ScriptUtils.DEFAULT_BLOCK_COMMENT_END_DELIMITER);
    } catch (SQLException e) {
      throw new IllegalStateException("Unable to initialize the database.", e);
    }
  }

  /**
   * @deprecated Use {@link #createTables(JdbcTemplate, Dialect)} or
   *     {@link #createTables(JdbcTemplate, Resource)}. Defaults to
   *     {@link Dialect#POSTGRESQL} for source compatibility.
   *
   *     <p>Scheduled for removal in 3.0.0. No caller is known — nothing in this repository uses
   *     it, and no other project in the ecosystem references {@code JdbcHelper} at all — but it
   *     is published API, so dropping it is a binary-compatibility break that belongs in a major
   *     release rather than a patch. Delete it, and its {@code java:S1133} suppression in
   *     SonarQube, as part of cutting 3.0.0.
   */
  @Deprecated(since = "2.2.0", forRemoval = true)
  public static void createTables(JdbcTemplate jdbcTemplate) {
    createTables(jdbcTemplate, Dialect.POSTGRESQL);
  }

  private static void logMetaData(DatabaseMetaData metaData, String schema) throws SQLException {
    if (log.isTraceEnabled()) {
      logTables(metaData, schema);
    }
  }

  private static void logTables(DatabaseMetaData metaData, String schema) throws SQLException {
    int i = 0;
    log.trace("No\tCatalog\tSchema\tTable\tType");
    log.trace("-----\t---------------\t---------------\t---------------\t---------------");
    try (ResultSet rs = metaData.getTables(null, schema, "%", new String[] {"TABLE"})) {
      while (rs.next()) {
        log.trace("{}\t{}\t{}\t{}\t{}", ++i,
            rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4));
      }
    }
  }

  /**
   * Returns an open connection with auto-commit disabled. Ownership passes to the caller, which
   * must close it — every call site does so in a {@code finally} via {@link #close(Connection)}.
   *
   * <p>The {@code setAutoCommit} call is guarded because it happens after the connection has been
   * handed over by the pool but before the caller can see it: if it throws, the caller never
   * receives the reference and cannot close it, so the connection would leak out of the pool for
   * good. Repeated over a flapping database that is exactly how a pool gets exhausted.
   */
  public static Connection getConnection(JdbcTemplate jdbcTemplate)
      throws SQLException {
    DataSource dataSource = Objects.requireNonNull(jdbcTemplate.getDataSource());
    Connection conn = dataSource.getConnection();
    try {
      conn.setAutoCommit(false);
      return conn;
    } catch (SQLException e) {
      close(conn);
      throw e;
    }
  }

  private static void logSql(PreparedStatement ps) {
    if (log.isTraceEnabled()) {
      String sql = ps.toString();
      int i = sql.indexOf("wrapping");
      if (i >= 0 && sql.length() >= (i + 9)) {
        sql = sql.substring(i + 9);
      }
      log.trace("Executing SQL: {}", sql);
    }
  }
}
