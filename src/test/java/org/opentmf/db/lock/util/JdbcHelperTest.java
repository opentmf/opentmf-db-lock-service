package org.opentmf.db.lock.util;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

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

  private static JdbcTemplate jdbcTemplateReturning(Connection conn) throws SQLException {
    DataSource dataSource = mock(DataSource.class);
    when(dataSource.getConnection()).thenReturn(conn);
    JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    when(jdbcTemplate.getDataSource()).thenReturn(dataSource);
    return jdbcTemplate;
  }
}
