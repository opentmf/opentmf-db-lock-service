package org.opentmf.db.lock.dialect;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.opentmf.db.lock.config.DbLockProperties;
import org.opentmf.db.lock.exception.DbLockException;
import org.opentmf.db.lock.exception.DbLockLostException;
import org.opentmf.db.lock.exception.DbLockTimeoutException;
import org.opentmf.db.lock.model.AcquiredLock;
import org.opentmf.db.lock.model.LockType;
import org.opentmf.db.lock.service.impl.DbLockServiceImpl;
import org.opentmf.db.lock.util.JdbcHelper;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Shared smoke-test contract for each supported dialect. Subclasses supply a {@link DataSource}
 * pointing at the target database (Testcontainer or in-memory). The tests verify that:
 * <ul>
 *   <li>the bundled DDL executes without error,</li>
 *   <li>the DDL is idempotent (running it a second time is a no-op),</li>
 *   <li>{@code acquireLock} -&gt; {@code releaseLock} round-trips populate each of the three
 *   tables as expected.</li>
 * </ul>
 *
 * @author Gokhan Demir
 */
abstract class AbstractDialectIT {

  private static final List<String> LOCK_TABLES =
      List.of("DB_LOCK_LATEST", "DB_LOCK_HISTORY", "DB_LOCK");

  protected abstract DataSource dataSource();

  protected abstract Dialect dialect();

  @Test
  void ddlIsIdempotentAndLockRoundTripPopulatesAllTables() throws Exception {
    JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource());

    JdbcHelper.createTables(jdbcTemplate, dialect());
    JdbcHelper.createTables(jdbcTemplate, dialect());

    DbLockServiceImpl service = new DbLockServiceImpl(jdbcTemplate, testProperties());

    assertEquals(0, countRows(jdbcTemplate, "DB_LOCK"));
    assertEquals(0, countRows(jdbcTemplate, "DB_LOCK_HISTORY"));
    assertEquals(0, countRows(jdbcTemplate, "DB_LOCK_LATEST"));

    AcquiredLock lock = service.acquireLock(LockType.LOCK_X, "1.0");
    assertNotNull(lock);
    assertTrue(service.hasLock(LockType.LOCK_X));
    assertEquals(1, countRows(jdbcTemplate, "DB_LOCK"));
    assertEquals(0, countRows(jdbcTemplate, "DB_LOCK_HISTORY"));
    assertEquals(0, countRows(jdbcTemplate, "DB_LOCK_LATEST"));

    service.releaseLock(lock, true);
    assertEquals(0, countRows(jdbcTemplate, "DB_LOCK"));
    assertEquals(1, countRows(jdbcTemplate, "DB_LOCK_HISTORY"));
    assertEquals(1, countRows(jdbcTemplate, "DB_LOCK_LATEST"));
    service.destroy();
  }

  /**
   * The bundled DDL must persist through a pool whose auto-commit default is off. Where DDL is
   * transactional (PostgreSQL, SQL Server, DB2) such a pool rolls back whatever is still
   * uncommitted when the connection goes back to it, so the tables would silently never appear.
   */
  @Test
  void ddlPersistsThroughAPoolWithAutoCommitOff() {
    JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource());
    for (String table : LOCK_TABLES) {
      dropIfPresent(jdbcTemplate, table);
    }

    HikariConfig config = new HikariConfig();
    config.setDataSource(dataSource());
    config.setAutoCommit(false);
    config.setMaximumPoolSize(1);
    try (HikariDataSource pool = new HikariDataSource(config)) {
      JdbcHelper.createTables(new JdbcTemplate(pool), dialect());
    }

    for (String table : LOCK_TABLES) {
      assertDoesNotThrow(() -> countRows(jdbcTemplate, table),
          () -> table + " must be visible to a second connection");
    }
  }

  /**
   * The first recorded release of a lock type inserts its {@code DB_LOCK_LATEST} row; every later
   * one updates it. That update must work on every dialect: when it fails, the release rolls back,
   * the lock stays held, and the latest version never moves past the first one recorded.
   */
  @Test
  void laterRecordedReleasesUpdateTheLatestLock() throws Exception {
    JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource());
    JdbcHelper.createTables(jdbcTemplate, dialect());
    DbLockServiceImpl service = new DbLockServiceImpl(jdbcTemplate, testProperties());
    try {
      service.releaseLock(service.acquireLock(LockType.LOCK_Y, "1.0"), true);
      AcquiredLock second = service.acquireLock(LockType.LOCK_Y, "2.0");
      assertTrue(second.isUpgrade());

      service.releaseLock(second, true);

      assertFalse(service.hasLock(LockType.LOCK_Y), "the release must not leave the lock held");
      assertEquals("2.0", jdbcTemplate.queryForObject(
          "select lock_version from DB_LOCK_LATEST where lock_type = 'Y'", String.class));
      AcquiredLock third = service.acquireLock(LockType.LOCK_Y, "2.0");
      assertTrue(third.isSameVersion(), "2.0 is already recorded, so it must not run again");
      service.releaseLock(third, false);
    } finally {
      service.destroy();
      jdbcTemplate.update("delete from DB_LOCK_LATEST where lock_type = 'Y'");
      jdbcTemplate.update("delete from DB_LOCK_HISTORY where lock_type = 'Y'");
    }
  }

  @Test
  void aWaiterSeesTheVersionRecordedByTheHolderItWaitedFor() throws Exception {
    assertAWaiterSeesTheVersionRecordedWhileItWaited(dataSource());
  }

  /**
   * Two instances of a new version start together: one takes the lock and records the version, the
   * other waits for it. When the waiter's insert finally wins it must see that version as recorded,
   * or it runs the same work a second time. Up to 2.2.2 the waiter inserted inside the transaction
   * its last poll had left open across the sleep, so under a snapshot isolation level — MySQL's
   * default REPEATABLE READ — it read {@code DB_LOCK_LATEST} as it was before the holder's release.
   */
  protected void assertAWaiterSeesTheVersionRecordedWhileItWaited(DataSource dataSource)
      throws Exception {
    JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
    JdbcHelper.createTables(jdbcTemplate, dialect());
    DbLockProperties props = testProperties();
    props.setLockAcquirePollInterval(1500L);
    props.setLockAcquireTimeout(10_000L);
    DbLockServiceImpl holderPod = new DbLockServiceImpl(jdbcTemplate, props);
    DbLockServiceImpl waiterPod = new DbLockServiceImpl(jdbcTemplate, props);
    ExecutorService executor = Executors.newSingleThreadExecutor();
    try {
      holderPod.releaseLock(holderPod.acquireLock(LockType.LOCK_Z, "1.0"), true);
      AcquiredLock holder = holderPod.acquireLock(LockType.LOCK_Z, "2.0");
      Future<AcquiredLock> waiter =
          executor.submit(() -> waiterPod.acquireLock(LockType.LOCK_Z, "2.0"));
      // The waiter loses its first insert at once and sleeps until its next poll; the holder
      // records 2.0 and releases during that sleep.
      await().pollDelay(Duration.ofMillis(500)).until(() -> true);
      holderPod.releaseLock(holder, true);

      AcquiredLock acquired = waiter.get(10, TimeUnit.SECONDS);
      waiterPod.releaseLock(acquired, false);
      assertTrue(acquired.isSameVersion(),
          () -> "2.0 was recorded while the waiter slept, yet it saw "
              + acquired.getPreviousLock());
    } finally {
      executor.shutdownNow();
      holderPod.destroy();
      waiterPod.destroy();
      jdbcTemplate.update("delete from DB_LOCK_LATEST where lock_type = 'Z'");
      jdbcTemplate.update("delete from DB_LOCK_HISTORY where lock_type = 'Z'");
    }
  }

  /** A lock held past the waiter's hold-timeout is reclaimed, on every dialect. */
  @Test
  void aLockHeldPastItsHoldTimeoutIsReclaimed() throws Exception {
    JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource());
    JdbcHelper.createTables(jdbcTemplate, dialect());
    DbLockServiceImpl holderPod = new DbLockServiceImpl(jdbcTemplate, testProperties());
    DbLockProperties waiterProps = testProperties();
    waiterProps.setLockHoldTimeout(1000L);
    waiterProps.setLockAcquireTimeout(5000L);
    DbLockServiceImpl waiterPod = new DbLockServiceImpl(jdbcTemplate, waiterProps);
    try {
      AcquiredLock stale = holderPod.acquireLock(LockType.BPMN, "1.0");
      await().pollDelay(Duration.ofMillis(1500)).until(() -> true);

      AcquiredLock reclaimed = waiterPod.acquireLock(LockType.BPMN, "1.0");
      waiterPod.releaseLock(reclaimed, false);

      assertEquals(1, jdbcTemplate.queryForObject(
          "select count(*) from DB_LOCK_HISTORY where lock_id = ?", Integer.class,
          stale.getLockId()));
    } finally {
      holderPod.destroy();
      waiterPod.destroy();
      jdbcTemplate.update("delete from DB_LOCK_HISTORY where lock_type = 'B'");
    }
  }

  @Test
  void aLockWithinItsHoldTimeoutIsNotReclaimed() throws Exception {
    assertALockWithinItsHoldTimeoutIsNotReclaimed(dataSource());
  }

  /**
   * A lock still within its hold-timeout is not reclaimed, neither by a waiter nor by the startup
   * sweep. The age of a lock has to be measured with the database's own clock: up to 2.2.2 it was
   * {@code created_on}, stamped by the database, against the JVM's clock, so with a plain
   * {@code TIMESTAMP} column (MySQL, DB2) read under a time zone other than the server's, a lock
   * one second old looked hours old and was taken from its live holder.
   */
  protected void assertALockWithinItsHoldTimeoutIsNotReclaimed(DataSource dataSource)
      throws Exception {
    JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
    JdbcHelper.createTables(jdbcTemplate, dialect());
    DbLockServiceImpl holderPod = new DbLockServiceImpl(jdbcTemplate, testProperties());
    DbLockServiceImpl waiterPod = new DbLockServiceImpl(jdbcTemplate, testProperties());
    try {
      AcquiredLock held = holderPod.acquireLock(LockType.BPMN, "1.0");

      assertThrows(DbLockTimeoutException.class,
          () -> waiterPod.acquireLock(LockType.BPMN, "1.0"));
      waiterPod.removeStaleLocks();

      assertTrue(holderPod.hasLock(LockType.BPMN), "the live holder must keep its lock");
      holderPod.releaseLock(held, false);
    } finally {
      holderPod.destroy();
      waiterPod.destroy();
      jdbcTemplate.update("delete from DB_LOCK where lock_type = 'B'");
      jdbcTemplate.update("delete from DB_LOCK_HISTORY where lock_type = 'B'");
    }
  }

  @Test
  void aRecentlyRecordedVersionDoesNotYetAllowADowngrade() throws Exception {
    assertARecentlyRecordedVersionDoesNotYetAllowADowngrade(dataSource());
  }

  /**
   * A downgrade is allowed only once the latest version has been recorded for longer than the
   * configured period, and that period has to be measured with the database's clock: the record
   * is stamped by the database, and with a plain {@code TIMESTAMP} column (MySQL, DB2) read in a
   * time zone other than the server's a record seconds old looked hours old to the JVM's clock.
   */
  protected void assertARecentlyRecordedVersionDoesNotYetAllowADowngrade(DataSource dataSource)
      throws Exception {
    JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
    JdbcHelper.createTables(jdbcTemplate, dialect());
    DbLockServiceImpl service = new DbLockServiceImpl(jdbcTemplate, testProperties());
    try {
      service.releaseLock(service.acquireLock(LockType.CATALOG, "2.0"), true);

      AcquiredLock downgrade = service.acquireLock(LockType.CATALOG, "1.0");
      service.releaseLock(downgrade, false);

      assertTrue(downgrade.isDowngrade());
      assertFalse(downgrade.isDowngradeAllowed(Duration.ofHours(1).toMillis()),
          () -> "2.0 was recorded seconds ago: " + downgrade.getPreviousLock());
    } finally {
      service.destroy();
      jdbcTemplate.update("delete from DB_LOCK_LATEST where lock_type = 'C'");
      jdbcTemplate.update("delete from DB_LOCK_HISTORY where lock_type = 'C'");
    }
  }

  /**
   * Two contenders, one lock. While the holder renews its lease, the waiter cannot take the lock
   * for two and a half hold-timeouts, and the holder's own hold-timeout does not release it either.
   * Once the renewals stop, the lease runs out and the waiter takes the lock — and the holder's
   * next renewal fails loudly instead of taking anything back from the new holder.
   */
  @Test
  void aRenewedLeaseIsNotStolenAndAnUnrenewedOneIs() throws Exception {
    JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource());
    JdbcHelper.createTables(jdbcTemplate, dialect());
    DbLockProperties props = testProperties();
    props.setLockHoldTimeout(2000L);
    props.setLockAcquireTimeout(5000L);
    DbLockServiceImpl holderPod = new DbLockServiceImpl(jdbcTemplate, props);
    DbLockServiceImpl waiterPod = new DbLockServiceImpl(jdbcTemplate, props);
    ScheduledExecutorService heartbeat = Executors.newSingleThreadScheduledExecutor();
    AtomicReference<DbLockException> failedBeat = new AtomicReference<>();
    try {
      AcquiredLock held = holderPod.acquireLock(LockType.LOCK_X, "1.0");
      ScheduledFuture<?> beats = heartbeat.scheduleAtFixedRate(() -> {
        try {
          holderPod.renewLock(held);
        } catch (DbLockException e) {
          failedBeat.compareAndSet(null, e);
        }
      }, 500, 500, TimeUnit.MILLISECONDS);

      assertThrows(DbLockTimeoutException.class,
          () -> waiterPod.acquireLock(LockType.LOCK_X, "1.0"));
      assertNull(failedBeat.get(), "every renewal within the lease must succeed");
      assertEquals(held.getLockId(), heldLockId(jdbcTemplate, LockType.LOCK_X),
          "the renewed lock must still be the holder's");

      beats.cancel(false);
      AcquiredLock taken = waiterPod.acquireLock(LockType.LOCK_X, "1.0");
      assertNotEquals(held.getLockId(), taken.getLockId());

      assertThrows(DbLockLostException.class, () -> holderPod.renewLock(held));
      assertEquals(taken.getLockId(), heldLockId(jdbcTemplate, LockType.LOCK_X),
          "a failed renewal must leave the new holder's lock alone");
      waiterPod.releaseLock(taken, false);
    } finally {
      heartbeat.shutdownNow();
      holderPod.destroy();
      waiterPod.destroy();
      jdbcTemplate.update("delete from DB_LOCK where lock_type = 'X'");
      jdbcTemplate.update("delete from DB_LOCK_HISTORY where lock_type = 'X'");
    }
  }

  /**
   * A waiter that finds the lock stale while its holder is renewing it must not remove the renewed
   * lock. The reclaim reads the lock without blocking, so it can see the old lease; it then waits
   * for the renewal's row lock and has to read the lock again before it decides.
   */
  @Test
  void aReclaimThatMeetsARenewalInFlightLeavesTheRenewedLease() throws Exception {
    JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource());
    JdbcHelper.createTables(jdbcTemplate, dialect());
    DbLockServiceImpl holderPod = new DbLockServiceImpl(jdbcTemplate, testProperties());
    DbLockProperties waiterProps = testProperties();
    // Long enough that the renewed lease cannot run out again before the waiter gives up, even
    // where the column keeps whole seconds only (MySQL).
    waiterProps.setLockHoldTimeout(3000L);
    waiterProps.setLockAcquireTimeout(1500L);
    DbLockServiceImpl waiterPod = new DbLockServiceImpl(jdbcTemplate, waiterProps);
    ExecutorService executor = Executors.newSingleThreadExecutor();
    try {
      AcquiredLock held = holderPod.acquireLock(LockType.CATALOG, "1.0");
      await().pollDelay(Duration.ofMillis(4500)).until(() -> true);

      try (Connection renewal = dataSource().getConnection()) {
        renewal.setAutoCommit(false);
        try (PreparedStatement ps = renewal.prepareStatement("update DB_LOCK set created_on = "
            + dialect().getCurrentTimestampSql() + " where id = ?")) {
          ps.setInt(1, held.getLockId());
          assertEquals(1, ps.executeUpdate());
        }
        Future<AcquiredLock> waiter =
            executor.submit(() -> waiterPod.acquireLock(LockType.CATALOG, "1.0"));
        // The waiter reads the old, stale lease and blocks on the renewal's row lock.
        await().pollDelay(Duration.ofMillis(500)).until(() -> true);
        renewal.commit();

        ExecutionException e = assertThrows(ExecutionException.class,
            () -> waiter.get(10, TimeUnit.SECONDS));
        assertInstanceOf(DbLockTimeoutException.class, e.getCause());
      }
      assertEquals(held.getLockId(), heldLockId(jdbcTemplate, LockType.CATALOG),
          "the renewed lock must still be the holder's");
      holderPod.releaseLock(held, false);
    } finally {
      executor.shutdownNow();
      holderPod.destroy();
      waiterPod.destroy();
      jdbcTemplate.update("delete from DB_LOCK where lock_type = 'C'");
      jdbcTemplate.update("delete from DB_LOCK_HISTORY where lock_type = 'C'");
    }
  }

  /**
   * A renewal after the lease ran out by the database's clock fails, even while this instance's own
   * hold-timeout has not fired yet and nobody has reclaimed the lock, and it releases the lock on
   * the spot rather than leaving it for a waiter's reclaim.
   */
  @Test
  void aRenewalAfterTheLeaseExpiredFailsAndReleasesTheLock() throws Exception {
    JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource());
    JdbcHelper.createTables(jdbcTemplate, dialect());
    DbLockServiceImpl pod = new DbLockServiceImpl(jdbcTemplate, testProperties());
    try {
      AcquiredLock stalled = pod.acquireLock(LockType.LOCK_Y, "1.0");
      // As the database sees a holder that stalled for a day: far past its 60 s lease, whatever
      // the time zones of the JVM and the server.
      jdbcTemplate.update("update DB_LOCK set created_on = ? where id = ?",
          Timestamp.from(Instant.now().minus(Duration.ofDays(1))), stalled.getLockId());

      assertThrows(DbLockLostException.class, () -> pod.renewLock(stalled));

      assertFalse(pod.hasLock(LockType.LOCK_Y), "the expired lock must be released");
      assertEquals(1, jdbcTemplate.queryForObject(
          "select count(*) from DB_LOCK_HISTORY where lock_id = ?", Integer.class,
          stalled.getLockId()));
    } finally {
      pod.destroy();
      jdbcTemplate.update("delete from DB_LOCK where lock_type = 'Y'");
      jdbcTemplate.update("delete from DB_LOCK_HISTORY where lock_type = 'Y'");
    }
  }

  @Test
  void aRenewalAfterTheReleaseFails() throws Exception {
    JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource());
    JdbcHelper.createTables(jdbcTemplate, dialect());
    DbLockServiceImpl pod = new DbLockServiceImpl(jdbcTemplate, testProperties());
    try {
      AcquiredLock lock = pod.acquireLock(LockType.LOCK_Z, "1.0");
      pod.releaseLock(lock, false);

      assertThrows(DbLockLostException.class, () -> pod.renewLock(lock));
      assertFalse(pod.hasLock(LockType.LOCK_Z));
    } finally {
      pod.destroy();
      jdbcTemplate.update("delete from DB_LOCK_HISTORY where lock_type = 'Z'");
    }
  }

  private static int heldLockId(JdbcTemplate jdbcTemplate, LockType lockType) {
    Integer id = jdbcTemplate.queryForObject("select id from DB_LOCK where lock_type = ?",
        Integer.class, lockType.getDbValue());
    return id == null ? -1 : id;
  }

  private static DbLockProperties testProperties() {
    DbLockProperties props = new DbLockProperties();
    props.setLockAcquirePollInterval(100L);
    props.setLockAcquireTimeout(1000L);
    props.setLockHoldTimeout(60_000L);
    return props;
  }

  private static void dropIfPresent(JdbcTemplate jdbcTemplate, String table) {
    try {
      jdbcTemplate.execute("drop table " + table);
    } catch (DataAccessException absent) {
      // nothing to drop
    }
  }

  private static int countRows(JdbcTemplate jdbcTemplate, String table) {
    Integer n = jdbcTemplate.queryForObject("select count(*) from " + table, Integer.class);
    return n == null ? 0 : n;
  }
}
