package org.opentmf.db.lock.dialect;

import static org.junit.jupiter.api.Assertions.assertEquals;

import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.opentmf.db.lock.util.JdbcHelper;
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
   * Installs an event trigger recording every {@code ALTER TABLE} executed from here on, and
   * clears anything recorded earlier.
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
            + "as $fn$ begin insert into ddl_audit(tag) values (tg_tag); end $fn$");
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
