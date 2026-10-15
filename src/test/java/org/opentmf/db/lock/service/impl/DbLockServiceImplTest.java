package org.opentmf.db.lock.service.impl;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.opentmf.db.lock.config.DbLockProperties;
import org.opentmf.db.lock.exception.DbLockException;
import org.opentmf.db.lock.exception.DbLockTimeoutException;
import org.opentmf.db.lock.model.LockType;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Transaction-boundary guarantees of {@link DbLockServiceImpl} that a real database would hide: the
 * pools in common use clean up after a sloppy caller, so only a mocked connection shows whether
 * the library ends its own transactions and gives its connections back as it found them.
 *
 * @author Gokhan Demir
 */
class DbLockServiceImplTest {

  /**
   * A failure other than the unique-index violation used to escape as a {@link DbLockException}
   * past the {@code catch (SQLException)} that rolled back, so the connection was closed with its
   * transaction still open and the pool decided what became of it.
   */
  @Test
  void acquireLock_whenTheInsertFailsForAnotherReason_rollsBackBeforeClosing()
      throws SQLException {
    Connection conn = connection(Connection.TRANSACTION_READ_COMMITTED);
    PreparedStatement insert = mock(PreparedStatement.class);
    when(conn.prepareStatement(anyString(), any(String[].class))).thenReturn(insert);
    when(insert.executeUpdate()).thenThrow(new SQLException("terminating connection", "57P01"));
    DbLockServiceImpl service = new DbLockServiceImpl(jdbcTemplateFor(conn), properties());

    assertThrows(DbLockException.class, () -> service.acquireLock(LockType.LOCK_X, "1"));

    InOrder order = inOrder(conn);
    order.verify(conn).rollback();
    order.verify(conn).close();
  }

  /**
   * The library switches auto-commit off and pins READ COMMITTED for its own transaction. A pool
   * that does not reset those on return would otherwise hand the next borrower — application code
   * expecting the pool's defaults — a connection silently running with auto-commit off.
   */
  @Test
  void aConnectionGoesBackWithItsOwnAutoCommitAndIsolation() throws SQLException {
    Connection conn = connection(Connection.TRANSACTION_SERIALIZABLE);
    PreparedStatement count = mock(PreparedStatement.class);
    ResultSet rs = mock(ResultSet.class);
    when(conn.prepareStatement(anyString())).thenReturn(count);
    when(count.executeQuery()).thenReturn(rs);
    when(rs.next()).thenReturn(true);
    DbLockServiceImpl service = new DbLockServiceImpl(jdbcTemplateFor(conn), properties());

    assertFalse(service.hasLock(LockType.LOCK_X));

    InOrder order = inOrder(conn);
    order.verify(conn).setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
    order.verify(conn).setAutoCommit(false);
    order.verify(conn).commit();
    order.verify(conn).setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE);
    order.verify(conn).setAutoCommit(true);
    order.verify(conn).close();
  }

  /**
   * An interrupted waiter must stop waiting. The sleep between polls used to swallow the
   * interrupt and re-set the flag, so every later sleep returned at once and the loop hammered the
   * database with failed inserts until the acquire timeout — 6,396 attempts in a 3-second window,
   * measured, where an uninterrupted waiter makes four.
   */
  @Test
  void acquireLock_whenTheWaitingThreadIsInterrupted_givesUpAtOnceAndKeepsTheInterrupt()
      throws SQLException {
    Connection conn = connection(Connection.TRANSACTION_READ_COMMITTED);
    PreparedStatement insert = mock(PreparedStatement.class);
    when(conn.prepareStatement(anyString(), any(String[].class))).thenReturn(insert);
    when(insert.executeUpdate()).thenThrow(new SQLException("duplicate key value", "23505"));
    PreparedStatement select = mock(PreparedStatement.class);
    when(conn.prepareStatement(anyString())).thenReturn(select);
    when(select.executeQuery()).thenReturn(mock(ResultSet.class));
    DbLockServiceImpl service = new DbLockServiceImpl(jdbcTemplateFor(conn), properties());

    Thread.currentThread().interrupt();
    DbLockException thrown;
    try {
      thrown = assertThrows(DbLockException.class,
          () -> service.acquireLock(LockType.LOCK_X, "1"));
    } finally {
      assertTrue(Thread.interrupted(), "the interrupt must be kept for the caller to see");
    }

    assertFalse(thrown instanceof DbLockTimeoutException, "it gave up, it did not time out");
    verify(insert, times(1)).executeUpdate();
  }

  /** A pooled connection as handed out with auto-commit on and the given isolation level. */
  private static Connection connection(int isolation) throws SQLException {
    Connection conn = mock(Connection.class);
    DatabaseMetaData metaData = mock(DatabaseMetaData.class);
    when(conn.getMetaData()).thenReturn(metaData);
    when(conn.getAutoCommit()).thenReturn(true);
    when(conn.getTransactionIsolation()).thenReturn(isolation);
    return conn;
  }

  private static JdbcTemplate jdbcTemplateFor(Connection conn) throws SQLException {
    DataSource dataSource = mock(DataSource.class);
    when(dataSource.getConnection()).thenReturn(conn);
    JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    when(jdbcTemplate.getDataSource()).thenReturn(dataSource);
    return jdbcTemplate;
  }

  private static DbLockProperties properties() {
    DbLockProperties props = new DbLockProperties();
    props.setLockAcquirePollInterval(100L);
    props.setLockAcquireTimeout(1000L);
    props.setLockHoldTimeout(60_000L);
    return props;
  }
}
