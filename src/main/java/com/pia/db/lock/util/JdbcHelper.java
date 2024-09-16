package com.pia.db.lock.util;

import com.pia.db.lock.model.LatestLock;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.OffsetDateTime;
import java.util.Objects;
import javax.sql.DataSource;
import lombok.Generated;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.lang.NonNull;
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
    try (PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
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

  public static void commit(Connection conn) {
    try {
      conn.commit();
    } catch (SQLException e) {
      log.warn("Ignoring exception during rollback.", e);
    }
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

  public static @NonNull LatestLock getLatestLock(Connection conn, String sql, String param)
      throws SQLException {
    try (PreparedStatement ps = conn.prepareStatement(sql)) {
      ps.setString(1, param);
      logSql(ps);
      ResultSet rs = ps.executeQuery();
      return rs.next()
          ? new LatestLock(rs.getString(1), rs.getObject(2, OffsetDateTime.class))
          : new LatestLock(null, null);
    }
  }

  public static void createTables(JdbcTemplate jdbcTemplate) {
    log.debug("In dbLockService.createTables()...");
    DataSource dataSource = jdbcTemplate.getDataSource();
    Assert.notNull(dataSource, "DataSource cannot be obtained during DB_Lock service init");
    try (Connection conn = DataSourceUtils.getConnection(dataSource)) {
      logMetaData(conn.getMetaData(), conn.getSchema());
      ScriptUtils.executeSqlScript(conn, new ClassPathResource("db/creation_script.sql"));
    } catch (SQLException e) {
      throw new IllegalStateException("Unable to initialize the database.", e);
    }
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
    for (ResultSet rs = metaData.getTables(null, schema, "%", new String[] {"TABLE"}); rs.next(); ) {
      log.trace("{}\t{}\t{}\t{}\t{}", ++i,
          rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4));
    }
  }

  public static Connection getConnection(JdbcTemplate jdbcTemplate)
      throws SQLException {
    DataSource dataSource = Objects.requireNonNull(jdbcTemplate.getDataSource());
    Connection conn = dataSource.getConnection();
    conn.setAutoCommit(false);
    return conn;
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
