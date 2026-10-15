package org.opentmf.db.lock.service.impl;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.startsWith;
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
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
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

  /**
   * Two waiters can both find the same stale lock. The one whose delete matches nothing — the
   * other already reclaimed it and wrote the history row — must roll back its own copy of that row
   * rather than commit a duplicate.
   */
  @Test
  void acquireLock_whenAnotherInstanceReclaimedTheStaleLockFirst_rollsBackItsHistoryCopy()
      throws SQLException, DbLockException {
    Connection conn = connection(Connection.TRANSACTION_READ_COMMITTED);
    PreparedStatement insert = mock(PreparedStatement.class);
    when(conn.prepareStatement(anyString(), any(String[].class))).thenReturn(insert);
    ResultSet generatedKey = mock(ResultSet.class);
    when(generatedKey.next()).thenReturn(true);
    when(generatedKey.getInt(1)).thenReturn(7);
    when(insert.executeUpdate())
        .thenThrow(new SQLException("duplicate key value", "23505"))
        .thenReturn(1);
    when(insert.getGeneratedKeys()).thenReturn(generatedKey);
    PreparedStatement existing = mock(PreparedStatement.class);
    ResultSet staleRow = mock(ResultSet.class);
    when(conn.prepareStatement(startsWith("select id, lock_version"))).thenReturn(existing);
    when(existing.executeQuery()).thenReturn(staleRow);
    when(staleRow.next()).thenReturn(true);
    when(staleRow.getInt("id")).thenReturn(5);
    when(staleRow.getObject("created_on", OffsetDateTime.class))
        .thenReturn(OffsetDateTime.now().minusHours(1));
    PreparedStatement history = mock(PreparedStatement.class);
    when(conn.prepareStatement(startsWith("insert into DB_LOCK_HISTORY"))).thenReturn(history);
    when(history.executeUpdate()).thenReturn(1);
    PreparedStatement delete = mock(PreparedStatement.class);
    when(conn.prepareStatement(startsWith("delete from DB_LOCK"))).thenReturn(delete);
    when(delete.executeUpdate()).thenReturn(0);
    PreparedStatement latest = mock(PreparedStatement.class);
    when(conn.prepareStatement(startsWith("select lock_version"))).thenReturn(latest);
    when(latest.executeQuery()).thenReturn(mock(ResultSet.class));
    DbLockServiceImpl service = new DbLockServiceImpl(jdbcTemplateFor(conn), properties());

    service.releaseLock(service.acquireLock(LockType.LOCK_X, "1"), false);

    InOrder order = inOrder(history, delete, conn, insert);
    order.verify(history).executeUpdate();
    order.verify(delete).executeUpdate();
    order.verify(conn).rollback();
    order.verify(insert).executeUpdate();
  }

  /**
   * Holding a lock must not cost a thread that keeps the JVM alive. Each lock used to start its own
   * non-daemon {@code java.util.Timer} thread for its hold-timeout.
   */
  @Test
  void holdTimeouts_startNoNonDaemonThreadPerLock() throws Exception {
    Connection conn = acquiringConnection();
    DbLockServiceImpl service = new DbLockServiceImpl(jdbcTemplateFor(conn), properties());
    Set<Thread> before = Thread.getAllStackTraces().keySet();

    service.acquireLock(LockType.LOCK_X, "1");
    service.acquireLock(LockType.LOCK_Y, "1");

    List<String> nonDaemon = Thread.getAllStackTraces().keySet().stream()
        .filter(t -> !before.contains(t) && !t.isDaemon())
        .map(Thread::getName)
        .toList();
    service.destroy();
    assertEquals(List.of(), nonDaemon);
  }

  /**
   * A hold-timeout runs once. When its release fails, the lock must not stay registered: past its
   * hold-timeout the row is any instance's to reclaim as stale, and the entry used to linger —
   * with its timer thread — for {@code destroy()} to try again.
   */
  @Test
  void aHoldTimeoutWhoseReleaseFails_isNotLeftBehind() throws Exception {
    Connection conn = acquiringConnection();
    PreparedStatement history = mock(PreparedStatement.class);
    when(conn.prepareStatement(startsWith("insert into DB_LOCK_HISTORY"))).thenReturn(history);
    when(history.executeUpdate()).thenThrow(new SQLException("database is down"));
    DbLockProperties props = properties();
    props.setLockHoldTimeout(200L);
    DbLockServiceImpl service = new DbLockServiceImpl(jdbcTemplateFor(conn), props);

    service.acquireLock(LockType.LOCK_X, "1");
    await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> verify(history).executeUpdate());
    await().pollDelay(Duration.ofMillis(300)).until(() -> true);
    service.destroy();

    verify(history, times(1)).executeUpdate();
  }

  /**
   * After {@code destroy()} no hold-timeout can be armed any more, so a lock taken then could only
   * ever be freed by another instance's stale reclaim. It has to be given back at once instead.
   */
  @Test
  void acquireLock_afterDestroy_givesTheLockBackAndFails() throws Exception {
    Connection conn = acquiringConnection();
    PreparedStatement history = mock(PreparedStatement.class);
    when(conn.prepareStatement(startsWith("insert into DB_LOCK_HISTORY"))).thenReturn(history);
    PreparedStatement delete = mock(PreparedStatement.class);
    when(conn.prepareStatement(startsWith("delete from DB_LOCK"))).thenReturn(delete);
    when(delete.executeUpdate()).thenReturn(1);
    DbLockServiceImpl service = new DbLockServiceImpl(jdbcTemplateFor(conn), properties());

    service.destroy();

    assertThrows(DbLockException.class, () -> service.acquireLock(LockType.LOCK_X, "1"));
    verify(delete).executeUpdate();
  }

  /** A connection on which an acquire succeeds (lock id 7, nothing recorded yet), and a release. */
  private static Connection acquiringConnection() throws SQLException {
    Connection conn = connection(Connection.TRANSACTION_READ_COMMITTED);
    PreparedStatement insert = mock(PreparedStatement.class);
    when(conn.prepareStatement(anyString(), any(String[].class))).thenReturn(insert);
    when(insert.executeUpdate()).thenReturn(1);
    ResultSet generatedKey = mock(ResultSet.class);
    when(generatedKey.next()).thenReturn(true);
    when(generatedKey.getInt(1)).thenReturn(7);
    when(insert.getGeneratedKeys()).thenReturn(generatedKey);
    PreparedStatement latest = mock(PreparedStatement.class);
    when(conn.prepareStatement(startsWith("select lock_version"))).thenReturn(latest);
    when(latest.executeQuery()).thenReturn(mock(ResultSet.class));
    PreparedStatement release = mock(PreparedStatement.class);
    when(release.executeUpdate()).thenReturn(1);
    when(conn.prepareStatement(startsWith("insert into DB_LOCK_HISTORY"))).thenReturn(release);
    when(conn.prepareStatement(startsWith("delete from DB_LOCK"))).thenReturn(release);
    return conn;
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
