package org.opentmf.db.lock.dialect;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Unit coverage for the branches {@code DialectPostgresIT} cannot reach against a real database:
 * the non-PostgreSQL short-circuit, a database that refuses the metadata lookup, an absent table,
 * and the upper-case identifier folding used by Oracle / DB2 / H2.
 *
 * @author Gokhan Demir
 */
class LockVersionMigrationTest {

  @ParameterizedTest
  @EnumSource(value = Dialect.class, names = "POSTGRESQL", mode = EnumSource.Mode.EXCLUDE)
  void widenLockVersion_isANoOpForEveryDialectExceptPostgresql(Dialect dialect) {
    JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);

    LockVersionMigration.widenLockVersion(jdbcTemplate, dialect);

    // Not even a DataSource lookup: no dialect other than PostgreSQL ever had a VARCHAR(10) era,
    // and the ALTER this class issues is PostgreSQL-specific syntax.
    verifyNoInteractions(jdbcTemplate);
  }

  @Test
  void widenLockVersion_whenTheDatabaseIsUnreachable_logsAndLetsStartupContinue()
      throws SQLException {
    DataSource dataSource = mock(DataSource.class);
    when(dataSource.getConnection()).thenThrow(new SQLException("database is down"));
    JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    when(jdbcTemplate.getDataSource()).thenReturn(dataSource);

    // The widening only matters to installs predating 2.0.0; a database that will not answer must
    // not stop an otherwise healthy application from booting.
    assertDoesNotThrow(
        () -> LockVersionMigration.widenLockVersion(jdbcTemplate, Dialect.POSTGRESQL));
  }

  @Test
  void widenLockVersion_whenTheColumnIsAbsent_issuesNoAlter() throws SQLException {
    Connection conn = connectionWithColumnWidth(null, false);

    LockVersionMigration.widenLockVersion(jdbcTemplateFor(conn), Dialect.POSTGRESQL);

    verify(conn, never()).createStatement();
  }

  @Test
  void widenLockVersion_whenTheColumnIsAlreadyWide_issuesNoAlter() throws SQLException {
    Connection conn = connectionWithColumnWidth(50, false);

    LockVersionMigration.widenLockVersion(jdbcTemplateFor(conn), Dialect.POSTGRESQL);

    verify(conn, never()).createStatement();
  }

  @Test
  void widenLockVersion_whenTheColumnIsNarrow_issuesTheLiteralAlterForEachTable()
      throws SQLException {
    Connection conn = connectionWithColumnWidth(10, false);
    Statement statement = mock(Statement.class);
    when(conn.createStatement()).thenReturn(statement);

    LockVersionMigration.widenLockVersion(jdbcTemplateFor(conn), Dialect.POSTGRESQL);

    verify(statement).execute("alter table DB_LOCK alter column lock_version type VARCHAR(50)");
    verify(statement)
        .execute("alter table DB_LOCK_HISTORY alter column lock_version type VARCHAR(50)");
    verify(statement)
        .execute("alter table DB_LOCK_LATEST alter column lock_version type VARCHAR(50)");
    verify(statement, times(3)).execute(anyString());
  }

  @Test
  void widenLockVersion_foldsIdentifiersToUpperCaseWhenTheDatabaseDoes() throws SQLException {
    Connection conn = connectionWithColumnWidth(50, true);

    LockVersionMigration.widenLockVersion(jdbcTemplateFor(conn), Dialect.POSTGRESQL);

    // Oracle / DB2 / H2 store unquoted identifiers upper-cased; the metadata lookup has to match.
    verify(conn.getMetaData()).getColumns(any(), any(), eq("DB_LOCK"), eq("LOCK_VERSION"));
  }

  private static Connection connectionWithColumnWidth(Integer width, boolean upperCase)
      throws SQLException {
    Connection conn = mock(Connection.class);
    DatabaseMetaData metaData = mock(DatabaseMetaData.class);
    ResultSet rs = mock(ResultSet.class);

    when(conn.getMetaData()).thenReturn(metaData);
    when(conn.getCatalog()).thenReturn("db");
    when(conn.getSchema()).thenReturn("public");
    when(metaData.storesUpperCaseIdentifiers()).thenReturn(upperCase);
    when(metaData.getColumns(any(), any(), anyString(), anyString())).thenReturn(rs);
    when(rs.next()).thenReturn(width != null);
    if (width != null) {
      when(rs.getInt("COLUMN_SIZE")).thenReturn(width);
    }
    return conn;
  }

  private static JdbcTemplate jdbcTemplateFor(Connection conn) throws SQLException {
    DataSource dataSource = mock(DataSource.class);
    when(dataSource.getConnection()).thenReturn(conn);
    JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    when(jdbcTemplate.getDataSource()).thenReturn(dataSource);
    return jdbcTemplate;
  }
}
