package org.opentmf.db.lock.dialect;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.opentmf.db.lock.config.DbLockProperties;
import org.opentmf.db.lock.model.AcquiredLock;
import org.opentmf.db.lock.model.LockType;
import org.opentmf.db.lock.service.impl.DbLockServiceImpl;
import org.opentmf.db.lock.util.JdbcHelper;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * PostgreSQL dialect tests: the shared {@link AbstractDialectIT} contract, plus regression guards
 * for the {@code lock_version} widening in {@code db/postgresql.sql}.
 *
 * <p>The widening is the legacy {@code VARCHAR(10) -> VARCHAR(50)} migration for installs created
 * before 2.0.0. It is guarded so the {@code ALTER} runs only on a column that is still narrower
 * than 50: re-running it on a healthy schema would take an {@code ACCESS EXCLUSIVE} table lock on
 * every application start, which lets concurrently booting contexts sharing one database deadlock.
 */
@Testcontainers
class DialectPostgresIT extends AbstractDialectIT {

  private static final int WIDENED_LENGTH = 50;
  private static final int LEGACY_LENGTH = 10;

  @Container
  static final PostgreSQLContainer<?> CONTAINER =
      new PostgreSQLContainer<>("postgres:16-alpine");

  @Override
  protected DataSource dataSource() {
    DriverManagerDataSource ds = new DriverManagerDataSource();
    ds.setDriverClassName(CONTAINER.getDriverClassName());
    ds.setUrl(CONTAINER.getJdbcUrl());
    ds.setUsername(CONTAINER.getUsername());
    ds.setPassword(CONTAINER.getPassword());
    return ds;
  }

  @Override
  protected Dialect dialect() {
    return Dialect.POSTGRESQL;
  }

  @Test
  void lockVersionWidening_isSkippedOnAnAlreadyWidenedSchema() {
    JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource());
    JdbcHelper.createTables(jdbcTemplate, Dialect.POSTGRESQL);
    assertLockVersionLength(jdbcTemplate, "db_lock", WIDENED_LENGTH);
    assertLockVersionLength(jdbcTemplate, "db_lock_history", WIDENED_LENGTH);
    assertLockVersionLength(jdbcTemplate, "db_lock_latest", WIDENED_LENGTH);

    installAlterTableAudit(jdbcTemplate);
    JdbcHelper.createTables(jdbcTemplate, Dialect.POSTGRESQL);

    assertEquals(0, auditedAlterTableCount(jdbcTemplate),
        "an already-widened schema must not re-run the widening ALTER: that re-takes the ACCESS "
            + "EXCLUSIVE lock which lets concurrently booting contexts deadlock");
    assertLockVersionLength(jdbcTemplate, "db_lock", WIDENED_LENGTH);
  }

  @Test
  void lockVersionWidening_stillMigratesALegacyColumn_exactlyOnce() {
    JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource());
    JdbcHelper.createTables(jdbcTemplate, Dialect.POSTGRESQL);
    jdbcTemplate.execute("alter table DB_LOCK alter column lock_version type VARCHAR(10)");
    assertLockVersionLength(jdbcTemplate, "db_lock", LEGACY_LENGTH);

    installAlterTableAudit(jdbcTemplate);
    JdbcHelper.createTables(jdbcTemplate, Dialect.POSTGRESQL);

    assertLockVersionLength(jdbcTemplate, "db_lock", WIDENED_LENGTH);
    assertEquals(1, auditedAlterTableCount(jdbcTemplate),
        "only the one legacy column should be widened; the two already-50 tables must be skipped");
  }

  @Test
  void lockVersionWidening_isNotTriggeredByALegacyTableInAnotherSchema() {
    JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource());
    JdbcHelper.createTables(jdbcTemplate, Dialect.POSTGRESQL);
    // A second tenant sharing the same database, still on the pre-2.0.0 VARCHAR(10) definition.
    // information_schema spans every schema the role can see, so an unqualified lookup would find
    // this row and re-ALTER our own already-widened table on every boot.
    jdbcTemplate.execute("create schema if not exists legacy_tenant");
    jdbcTemplate.execute("drop table if exists legacy_tenant.db_lock");
    jdbcTemplate.execute("create table legacy_tenant.db_lock (lock_version VARCHAR(10))");
    try {
      installAlterTableAudit(jdbcTemplate);
      JdbcHelper.createTables(jdbcTemplate, Dialect.POSTGRESQL);

      assertEquals(0, auditedAlterTableCount(jdbcTemplate),
          "another schema's legacy DB_LOCK must not trigger a widening of ours");
      assertLockVersionLength(jdbcTemplate, "legacy_tenant", "db_lock", LEGACY_LENGTH);
    } finally {
      jdbcTemplate.execute("drop schema legacy_tenant cascade");
    }
  }

  @Test
  void lockVersionWidening_leavesADeliberatelyWiderColumnAlone() {
    JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource());
    JdbcHelper.createTables(jdbcTemplate, Dialect.POSTGRESQL);
    jdbcTemplate.execute("alter table DB_LOCK alter column lock_version type VARCHAR(255)");
    try {
      installAlterTableAudit(jdbcTemplate);
      JdbcHelper.createTables(jdbcTemplate, Dialect.POSTGRESQL);

      assertEquals(0, auditedAlterTableCount(jdbcTemplate),
          "a column widened past 50 must not be narrowed back: that aborts startup as soon as a "
              + "stored lock_version exceeds 50 characters");
      assertLockVersionLength(jdbcTemplate, "db_lock", 255);
    } finally {
      jdbcTemplate.execute("alter table DB_LOCK alter column lock_version type VARCHAR(50)");
    }
  }

  /**
   * A user-supplied {@code ddl-location} script has no COMMIT of its own. Under a pool whose
   * auto-commit default is off, which rolls back whatever is still open when a connection goes back
   * to it, its tables must still be there for a second connection.
   */
  @Test
  void createTables_withADdlLocationScript_underAPoolWithAutoCommitOff_leavesTheTableBehind() {
    String schema = freshSchema("ac_off_user");
    try (HikariDataSource pool = pool(schema, false)) {
      JdbcHelper.createTables(new JdbcTemplate(pool),
          script("create table if not exists USER_DB_LOCK (id int primary key);"));
    }

    assertEquals(List.of("user_db_lock"), tablesIn(schema));
  }

  @Test
  void lockVersionWidening_underAPoolWithAutoCommitOff_isCommitted() {
    String schema = freshSchema("ac_off_widen");
    JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource());
    for (String table : List.of("db_lock", "db_lock_history", "db_lock_latest")) {
      jdbcTemplate.execute(
          "create table " + schema + "." + table
              + " (lock_version VARCHAR(" + LEGACY_LENGTH + "))");
    }
    try (HikariDataSource pool = pool(schema, false)) {
      LockVersionMigration.widenLockVersion(new JdbcTemplate(pool), Dialect.POSTGRESQL);
    }

    assertLockVersionLength(jdbcTemplate, schema, "db_lock", WIDENED_LENGTH);
    assertLockVersionLength(jdbcTemplate, schema, "db_lock_history", WIDENED_LENGTH);
    assertLockVersionLength(jdbcTemplate, schema, "db_lock_latest", WIDENED_LENGTH);
  }

  /**
   * A script that fails part-way must be undone as a whole, and the connection must go back to the
   * pool clean: no transaction left open (let alone an aborted one, which would fail every
   * statement of the next borrower) and the pool's own auto-commit setting restored.
   */
  @Test
  void createTables_whenTheScriptFails_rollsItBackAndReturnsACleanConnection() throws Exception {
    String schema = freshSchema("failing_script");
    try (HikariDataSource pool = pool(schema, true)) {
      JdbcTemplate jdbcTemplate = new JdbcTemplate(pool);
      Resource failing = script("""
          begin transaction;
          create table USER_DB_LOCK (id int primary key);
          select * from no_such_table;
          commit transaction;""");

      assertThrows(RuntimeException.class, () -> JdbcHelper.createTables(jdbcTemplate, failing));

      assertEquals(List.of(), tablesIn(schema), "a failed script must leave nothing behind");
      try (Connection conn = pool.getConnection(); Statement st = conn.createStatement()) {
        assertTrue(conn.getAutoCommit(), "the pool's auto-commit setting must be restored");
        assertDoesNotThrow(() -> st.execute("select 1"),
            "the pooled connection must not be left inside an aborted transaction");
      }
    }
  }

  /** The shared waiter scenario on a pool set to REPEATABLE READ, the protocol's worst case. */
  @Test
  void aWaiterSeesTheVersionRecordedByTheHolderItWaitedFor_underRepeatableRead()
      throws Exception {
    String schema = freshSchema("repeatable_read");
    HikariConfig config = poolConfig(schema, false, 4);
    config.setTransactionIsolation("TRANSACTION_REPEATABLE_READ");
    try (HikariDataSource pool = new HikariDataSource(config)) {
      assertAWaiterSeesTheVersionRecordedWhileItWaited(pool);
    }
  }

  /**
   * A waiter holds no connection, and so no transaction, while it sleeps between polls. Up to 2.2.2
   * it kept its pooled connection for the whole wait — one per waiter, for up to the acquire
   * timeout — and from 2.2.1 sat {@code idle in transaction} on it, holding locks on DB_LOCK that
   * queue any DDL against the table and make an {@code idle_in_transaction_session_timeout} shorter
   * than the poll interval kill the waiter.
   */
  @Test
  void aWaiterHoldsNoConnectionWhileItSleepsBetweenPolls() throws Exception {
    String schema = freshSchema("waiting");
    try (HikariDataSource pool = pool(schema, false, 4)) {
      JdbcTemplate jdbcTemplate = new JdbcTemplate(pool);
      JdbcHelper.createTables(jdbcTemplate, Dialect.POSTGRESQL);
      DbLockProperties props = new DbLockProperties();
      props.setLockAcquirePollInterval(1500L);
      props.setLockAcquireTimeout(10_000L);
      DbLockServiceImpl holderPod = new DbLockServiceImpl(jdbcTemplate, props);
      DbLockServiceImpl waiterPod = new DbLockServiceImpl(jdbcTemplate, props);
      ExecutorService executor = Executors.newSingleThreadExecutor();
      try {
        AcquiredLock holder = holderPod.acquireLock(LockType.LOCK_X, "1");
        Future<AcquiredLock> waiter =
            executor.submit(() -> waiterPod.acquireLock(LockType.LOCK_X, "1"));
        await().pollDelay(Duration.ofMillis(500)).until(() -> true);

        int active = pool.getHikariPoolMXBean().getActiveConnections();
        List<String> idleInTransaction = new JdbcTemplate(dataSource()).queryForList(
            "select query from pg_stat_activity where state = 'idle in transaction'"
                + " and query ilike '%db_lock%'",
            String.class);
        holderPod.releaseLock(holder, false);
        waiterPod.releaseLock(waiter.get(10, TimeUnit.SECONDS), false);

        assertEquals(0, active, "the sleeping waiter must not keep a pooled connection");
        assertEquals(List.of(), idleInTransaction, "nobody may sit idle in transaction");
      } finally {
        executor.shutdownNow();
        holderPod.destroy();
        waiterPod.destroy();
      }
    }
  }

  /**
   * Two releasers of one lock — the hold-timeout firing just as the holder releases — must leave
   * one history row, not two. Each copies the lock row into the history before deleting it, and
   * under READ COMMITTED the second still sees the row while the first's delete is uncommitted. The
   * one whose delete then finds the row gone has to roll its copy back.
   */
  @Test
  void racingReleasersOfOneLockLeaveASingleHistoryRow() throws Exception {
    String schema = freshSchema("racing_release");
    try (HikariDataSource pool = pool(schema, true, 4)) {
      JdbcTemplate jdbcTemplate = new JdbcTemplate(pool);
      JdbcHelper.createTables(jdbcTemplate, Dialect.POSTGRESQL);
      DbLockServiceImpl service = new DbLockServiceImpl(jdbcTemplate, new DbLockProperties());
      AcquiredLock lock = service.acquireLock(LockType.LOCK_Y, "1");
      ExecutorService executor = Executors.newSingleThreadExecutor();
      try (Connection firstReleaser = pool.getConnection();
          Statement first = firstReleaser.createStatement()) {
        firstReleaser.setAutoCommit(false);
        first.execute("insert into DB_LOCK_HISTORY "
            + "(lock_id, lock_type, lock_version, hostname, lock_acquired_on) "
            + "select id, lock_type, lock_version, hostname, created_on from DB_LOCK "
            + "where id = " + lock.getLockId());
        first.execute("delete from DB_LOCK where id = " + lock.getLockId());
        Future<Void> second = executor.submit(() -> {
          service.releaseLock(lock, false);
          return null;
        });
        // The second releaser has copied the still-visible row and now waits on the first's delete.
        await().atMost(Duration.ofSeconds(10))
            .until(() -> waitingOnARowLock("delete from DB_LOCK"));
        firstReleaser.commit();
        second.get(10, TimeUnit.SECONDS);
      } finally {
        executor.shutdownNow();
        service.destroy();
      }

      assertEquals(1, jdbcTemplate.queryForObject(
          "select count(*) from DB_LOCK_HISTORY where lock_id = ?", Integer.class,
          lock.getLockId()));
    }
  }

  /**
   * Instances starting together against an empty schema must all get their tables. {@code CREATE
   * TABLE IF NOT EXISTS} is not safe against a concurrent creator on PostgreSQL: once the first
   * creator commits, the others fail with a duplicate key in {@code pg_class}, and up to 2.2.2 that
   * failed their startup — two instances out of three, every round, measured.
   */
  @Test
  void instancesStartingTogetherOnAnEmptySchema_allCreateTheTables() throws Exception {
    for (int round = 0; round < 3; round++) {
      String schema = freshSchema("first_boot_" + round);
      List<HikariDataSource> pools = new ArrayList<>();
      CountDownLatch go = new CountDownLatch(1);
      ExecutorService executor = Executors.newFixedThreadPool(3);
      try {
        List<Future<Void>> starts = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
          HikariDataSource pool = pool(schema, true, 1);
          pools.add(pool);
          try (Connection warm = pool.getConnection()) {
            // connected up front, so the instances race on the DDL rather than on connecting
          }
          starts.add(executor.submit(() -> {
            go.await();
            JdbcHelper.createTables(new JdbcTemplate(pool), Dialect.POSTGRESQL);
            return null;
          }));
        }
        go.countDown();
        for (Future<Void> start : starts) {
          start.get(30, TimeUnit.SECONDS);
        }
      } finally {
        executor.shutdownNow();
        pools.forEach(HikariDataSource::close);
      }
      assertEquals(List.of("db_lock", "db_lock_history", "db_lock_latest"), tablesIn(schema));
    }
  }

  private boolean waitingOnARowLock(String queryPrefix) {
    Integer waiting = new JdbcTemplate(dataSource()).queryForObject(
        "select count(*) from pg_stat_activity where wait_event_type = 'Lock' and query ilike ?",
        Integer.class, queryPrefix + "%");
    return waiting != null && waiting > 0;
  }

  private String freshSchema(String schema) {
    JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource());
    jdbcTemplate.execute("drop schema if exists " + schema + " cascade");
    jdbcTemplate.execute("create schema " + schema);
    return schema;
  }

  /** A single-connection Hikari pool, so a test can borrow back the very connection used. */
  private static HikariDataSource pool(String schema, boolean autoCommit) {
    return pool(schema, autoCommit, 1);
  }

  private static HikariDataSource pool(String schema, boolean autoCommit, int size) {
    return new HikariDataSource(poolConfig(schema, autoCommit, size));
  }

  /**
   * The schema goes into the URL rather than {@link HikariConfig#setSchema}: pgjdbc runs
   * {@code setSchema} as a statement, which on an auto-commit-off pool opens a transaction of its
   * own and would make every pooled connection look {@code idle in transaction}.
   */
  private static HikariConfig poolConfig(String schema, boolean autoCommit, int size) {
    String url = CONTAINER.getJdbcUrl();
    HikariConfig config = new HikariConfig();
    config.setJdbcUrl(url + (url.contains("?") ? "&" : "?") + "currentSchema=" + schema);
    config.setUsername(CONTAINER.getUsername());
    config.setPassword(CONTAINER.getPassword());
    config.setAutoCommit(autoCommit);
    config.setMaximumPoolSize(size);
    return config;
  }

  private static Resource script(String sql) {
    return new ByteArrayResource(sql.getBytes(StandardCharsets.UTF_8));
  }

  private List<String> tablesIn(String schema) {
    return new JdbcTemplate(dataSource()).queryForList(
        "select table_name from information_schema.tables where table_schema = ? order by 1",
        String.class, schema);
  }

  /**
   * Installs an event trigger recording every {@code ALTER TABLE} executed from here on, and
   * clears anything recorded earlier. The trigger is database-wide and outlives the test, so its
   * function names the audit table with its schema: an ALTER issued under another
   * {@code search_path} must still find it rather than fail.
   *
   * <p>This is what makes these tests real regression guards. Asserting only "the width is still
   * 50" would pass just as well against the old unguarded script — the ALTER itself is what has to
   * be observed, not its result. Observing it via a table rewrite does not work either: PostgreSQL
   * skips the rewrite for a same-width varchar retype, yet still takes the ACCESS EXCLUSIVE lock
   * that caused the boot deadlock.
   */
  private static void installAlterTableAudit(JdbcTemplate jdbcTemplate) {
    jdbcTemplate.execute("create table if not exists ddl_audit (tag text not null)");
    jdbcTemplate.execute(
        "create or replace function record_alter_table() returns event_trigger language plpgsql "
            + "as $fn$ begin insert into public.ddl_audit(tag) values (tg_tag); end $fn$");
    jdbcTemplate.execute("drop event trigger if exists audit_alter_table");
    jdbcTemplate.execute(
        "create event trigger audit_alter_table on ddl_command_end "
            + "when tag in ('ALTER TABLE') execute function record_alter_table()");
    jdbcTemplate.update("delete from ddl_audit");
  }

  private static int auditedAlterTableCount(JdbcTemplate jdbcTemplate) {
    Integer n = jdbcTemplate.queryForObject("select count(*) from ddl_audit", Integer.class);
    return n == null ? 0 : n;
  }

  private static void assertLockVersionLength(
      JdbcTemplate jdbcTemplate, String tableName, int expected) {
    assertLockVersionLength(jdbcTemplate, "public", tableName, expected);
  }

  private static void assertLockVersionLength(
      JdbcTemplate jdbcTemplate, String schemaName, String tableName, int expected) {
    Integer length =
        jdbcTemplate.queryForObject(
            "select character_maximum_length from information_schema.columns "
                + "where table_schema = ? and table_name = ? and column_name = 'lock_version'",
            Integer.class,
            schemaName,
            tableName);
    assertEquals(expected, length,
        () -> "lock_version width mismatch for " + schemaName + "." + tableName);
  }
}
