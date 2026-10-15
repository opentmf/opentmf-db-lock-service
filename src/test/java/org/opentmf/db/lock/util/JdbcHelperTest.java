package org.opentmf.db.lock.util;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptStatementFailedException;

/**
 * @author Gokhan Demir
 */
class JdbcHelperTest {

  @Test
  void getConnection_disablesAutoCommit_andHandsTheConnectionToTheCaller() throws SQLException {
    Connection conn = mock(Connection.class);

    Connection returned = JdbcHelper.getConnection(jdbcTemplateReturning(conn));

    assertSame(conn, returned, "the caller must receive the connection it will later close");
    verify(conn).setAutoCommit(false);
    verify(conn, never()).close();
  }

  /**
   * Regression guard: {@code setAutoCommit} runs after the pool has handed the connection over but
   * before the caller can see it. If it throws and the connection is not closed here, the caller
   * never receives the reference and can never close it, so the connection leaks out of the pool
   * permanently.
   */
  @Test
  void getConnection_whenDisablingAutoCommitFails_closesTheConnectionInsteadOfLeakingIt()
      throws SQLException {
    Connection conn = mock(Connection.class);
    SQLException failure = new SQLException("connection went away");
    doThrow(failure).when(conn).setAutoCommit(false);

    JdbcTemplate jdbcTemplate = jdbcTemplateReturning(conn);
    SQLException thrown =
        assertThrows(SQLException.class, () -> JdbcHelper.getConnection(jdbcTemplate));

    assertSame(failure, thrown, "the original failure must propagate unchanged");
    verify(conn).close();
  }

  @Test
  void getConnection_whenClosingAlsoFails_stillPropagatesTheOriginalFailure() throws SQLException {
    Connection conn = mock(Connection.class);
    SQLException failure = new SQLException("connection went away");
    doThrow(failure).when(conn).setAutoCommit(false);
    doThrow(new SQLException("close failed too")).when(conn).close();

    JdbcTemplate jdbcTemplate = jdbcTemplateReturning(conn);
    SQLException thrown =
        assertThrows(SQLException.class, () -> JdbcHelper.getConnection(jdbcTemplate));

    assertSame(failure, thrown, "a failing close must not mask the original failure");
  }

  @Test
  void close_swallowsFailures_andToleratesNull() throws SQLException {
    Connection conn = mock(Connection.class);
    doThrow(new SQLException("already dead")).when(conn).close();

    assertDoesNotThrow(() -> JdbcHelper.close(conn));
    assertDoesNotThrow(() -> JdbcHelper.close(null));
    verify(conn).close();
  }

  @Test
  void rollback_swallowsFailures_andToleratesNull() throws SQLException {
    Connection conn = mock(Connection.class);
    doThrow(new SQLException("no transaction")).when(conn).rollback();

    assertDoesNotThrow(() -> JdbcHelper.rollback(conn));
    assertDoesNotThrow(() -> JdbcHelper.rollback(null));
    verify(conn).rollback();
  }

  /**
   * The DDL owns its transaction: on a pool whose auto-commit default is off the pool would
   * otherwise roll the uncommitted DDL back when the connection is returned, and on one whose
   * default is on that default must survive the call.
   */
  @Test
  void createTables_commitsTheScriptAsItsOwnTransaction_andRestoresAutoCommit()
      throws SQLException {
    Connection conn = scriptConnection();
    Statement statement = scriptStatement();
    when(conn.createStatement()).thenReturn(statement);

    JdbcHelper.createTables(jdbcTemplateReturning(conn), script("create table T (id int);"));

    InOrder order = inOrder(conn);
    order.verify(conn).setAutoCommit(false);
    order.verify(conn).commit();
    order.verify(conn).setAutoCommit(true);
    verify(conn, never()).rollback();
  }

  @Test
  void createTables_whenAStatementFails_rollsBackAndRestoresAutoCommitBeforePropagating()
      throws SQLException {
    Connection conn = scriptConnection();
    Statement statement = scriptStatement();
    when(conn.createStatement()).thenReturn(statement);
    when(statement.execute(anyString())).thenThrow(new SQLException("relation does not exist"));
    JdbcTemplate jdbcTemplate = jdbcTemplateReturning(conn);
    Resource script = script("create table T (id int);");

    assertThrows(ScriptStatementFailedException.class,
        () -> JdbcHelper.createTables(jdbcTemplate, script));

    InOrder order = inOrder(conn);
    order.verify(conn).rollback();
    order.verify(conn).setAutoCommit(true);
    verify(conn, never()).commit();
  }

  @Test
  void createTables_whenRestoringAutoCommitFails_keepsTheCommittedScript() throws SQLException {
    Connection conn = scriptConnection();
    Statement statement = scriptStatement();
    when(conn.createStatement()).thenReturn(statement);
    doThrow(new SQLException("connection lost")).when(conn).setAutoCommit(true);
    JdbcTemplate jdbcTemplate = jdbcTemplateReturning(conn);
    Resource script = script("create table T (id int);");

    assertDoesNotThrow(() -> JdbcHelper.createTables(jdbcTemplate, script));

    verify(conn).commit();
  }

  @Test
  void inTransaction_whenPreparingTheConnectionFails_closesItAndPropagates() throws SQLException {
    Connection conn = mock(Connection.class);
    SQLException failure = new SQLException("connection went away");
    when(conn.getAutoCommit()).thenThrow(failure);
    JdbcTemplate jdbcTemplate = jdbcTemplateReturning(conn);

    SQLException thrown = assertThrows(SQLException.class,
        () -> JdbcHelper.inTransaction(jdbcTemplate, c -> "never runs"));

    assertSame(failure, thrown);
    verify(conn).close();
    verify(conn, never()).commit();
  }

  @Test
  void inTransaction_whenTheWorkThrowsAnything_rollsBackAndStillGivesTheConnectionBack()
      throws SQLException {
    Connection conn = scriptConnection();
    JdbcTemplate jdbcTemplate = jdbcTemplateReturning(conn);

    assertThrows(IllegalArgumentException.class, () -> JdbcHelper.inTransaction(jdbcTemplate,
        c -> {
          throw new IllegalArgumentException("Insert statement did not produce a generated key.");
        }));

    InOrder order = inOrder(conn);
    order.verify(conn).rollback();
    order.verify(conn).setAutoCommit(true);
    order.verify(conn).close();
    verify(conn, never()).commit();
  }

  @Test
  void inTransaction_whenRestoringTheSettingsFails_stillClosesAndReturnsTheResult()
      throws SQLException {
    Connection conn = scriptConnection();
    when(conn.getTransactionIsolation()).thenReturn(Connection.TRANSACTION_SERIALIZABLE);
    doThrow(new SQLException("connection lost"))
        .when(conn).setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE);
    JdbcTemplate jdbcTemplate = jdbcTemplateReturning(conn);

    assertSame("done", JdbcHelper.inTransaction(jdbcTemplate, c -> "done"));

    verify(conn).commit();
    verify(conn).close();
  }

  /** An auto-commit-on connection whose metadata answers the trace-level table listing. */
  private static Connection scriptConnection() throws SQLException {
    Connection conn = mock(Connection.class);
    DatabaseMetaData metaData = mock(DatabaseMetaData.class);
    when(metaData.getTables(any(), any(), anyString(), any())).thenReturn(mock(ResultSet.class));
    when(conn.getMetaData()).thenReturn(metaData);
    when(conn.getAutoCommit()).thenReturn(true);
    return conn;
  }

  /** A statement reporting no further results, so ScriptUtils stops draining them. */
  private static Statement scriptStatement() throws SQLException {
    Statement statement = mock(Statement.class);
    when(statement.getUpdateCount()).thenReturn(-1);
    return statement;
  }

  private static Resource script(String sql) {
    return new ByteArrayResource(sql.getBytes(StandardCharsets.UTF_8));
  }

  private static JdbcTemplate jdbcTemplateReturning(Connection conn) throws SQLException {
    DataSource dataSource = mock(DataSource.class);
    when(dataSource.getConnection()).thenReturn(conn);
    JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    when(jdbcTemplate.getDataSource()).thenReturn(dataSource);
    return jdbcTemplate;
  }
}
