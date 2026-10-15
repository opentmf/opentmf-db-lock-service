package org.opentmf.db.lock.dialect;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.Statement;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
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
          "create table " + schema + "." + table + " (lock_version VARCHAR(" + LEGACY_LENGTH + "))");
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
      Resource failing = script("begin transaction;\n"
          + "create table USER_DB_LOCK (id int primary key);\n"
          + "select * from no_such_table;\n"
          + "commit transaction;");

      assertThrows(RuntimeException.class, () -> JdbcHelper.createTables(jdbcTemplate, failing));

      assertEquals(List.of(), tablesIn(schema), "a failed script must leave nothing behind");
      try (Connection conn = pool.getConnection(); Statement st = conn.createStatement()) {
        assertTrue(conn.getAutoCommit(), "the pool's auto-commit setting must be restored");
        assertDoesNotThrow(() -> st.execute("select 1"),
            "the pooled connection must not be left inside an aborted transaction");
      }
    }
  }

  private String freshSchema(String schema) {
    JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource());
    jdbcTemplate.execute("drop schema if exists " + schema + " cascade");
    jdbcTemplate.execute("create schema " + schema);
    return schema;
  }

  /** A single-connection Hikari pool, so a test can borrow back the very connection used. */
  private static HikariDataSource pool(String schema, boolean autoCommit) {
    HikariConfig config = new HikariConfig();
    config.setJdbcUrl(CONTAINER.getJdbcUrl());
    config.setUsername(CONTAINER.getUsername());
    config.setPassword(CONTAINER.getPassword());
    config.setSchema(schema);
    config.setAutoCommit(autoCommit);
    config.setMaximumPoolSize(1);
    return new HikariDataSource(config);
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
