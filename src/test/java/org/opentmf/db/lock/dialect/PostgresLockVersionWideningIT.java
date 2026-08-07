package org.opentmf.db.lock.dialect;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import org.opentmf.db.lock.util.JdbcHelper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Regression test for the PostgreSQL {@code lock_version} widening.
 *
 * <p>The bundled {@code db/postgresql.sql} declares {@code lock_version} as {@code VARCHAR(50)} in
 * its {@code CREATE TABLE} statements and guards the legacy {@code VARCHAR(10) -> VARCHAR(50)}
 * widening so the {@code ALTER} runs only when the column is still narrower than 50. This test
 * asserts that:
 *
 * <ul>
 *   <li>a fresh install has {@code lock_version} at {@code character_maximum_length = 50} on all
 *       three lock tables (the CREATE already gives 50, so no ALTER is needed);
 *   <li>running the script a second time is idempotent and does not throw, because the guard skips
 *       the ALTER on an already-widened schema — so no per-boot ACCESS EXCLUSIVE table lock is
 *       taken, which is the condition that let concurrently booting contexts deadlock;
 *   <li>a legacy {@code VARCHAR(10)} column is still widened back to 50 on the next run, so the
 *       one-time migration is preserved.
 * </ul>
 *
 * @author Yusuf Bozkurt
 */
@Testcontainers
class PostgresLockVersionWideningIT {

  private static final int WIDENED_LENGTH = 50;
  private static final int LEGACY_LENGTH = 10;

  @Container
  static final PostgreSQLContainer<?> CONTAINER = new PostgreSQLContainer<>("postgres:16-alpine");

  private static JdbcTemplate newJdbcTemplate() {
    DriverManagerDataSource dataSource = new DriverManagerDataSource();
    dataSource.setDriverClassName(CONTAINER.getDriverClassName());
    dataSource.setUrl(CONTAINER.getJdbcUrl());
    dataSource.setUsername(CONTAINER.getUsername());
    dataSource.setPassword(CONTAINER.getPassword());
    return new JdbcTemplate(dataSource);
  }

  @Test
  void createTables_widensLockVersionTo50_isIdempotent_andStillMigratesLegacyWidth() {
    JdbcTemplate jdbcTemplate = newJdbcTemplate();

    // Fresh install: the CREATE TABLE statements already declare VARCHAR(50).
    JdbcHelper.createTables(jdbcTemplate, Dialect.POSTGRESQL);
    assertLockVersionLength(jdbcTemplate, "db_lock", WIDENED_LENGTH);
    assertLockVersionLength(jdbcTemplate, "db_lock_history", WIDENED_LENGTH);
    assertLockVersionLength(jdbcTemplate, "db_lock_latest", WIDENED_LENGTH);

    // Idempotent: re-running must not throw and must leave the width unchanged. The guarded ALTER
    // is skipped because the columns are already VARCHAR(50), so no ACCESS EXCLUSIVE lock is taken.
    assertDoesNotThrow(() -> JdbcHelper.createTables(jdbcTemplate, Dialect.POSTGRESQL));
    assertLockVersionLength(jdbcTemplate, "db_lock", WIDENED_LENGTH);
    assertLockVersionLength(jdbcTemplate, "db_lock_history", WIDENED_LENGTH);
    assertLockVersionLength(jdbcTemplate, "db_lock_latest", WIDENED_LENGTH);

    // Legacy migration preserved: a column left at the pre-2.0.0 VARCHAR(10) is widened back to 50
    // on the next run, because the guard detects the narrower width and performs the one-time ALTER.
    jdbcTemplate.execute("ALTER TABLE DB_LOCK ALTER COLUMN lock_version TYPE VARCHAR(10)");
    assertLockVersionLength(jdbcTemplate, "db_lock", LEGACY_LENGTH);

    JdbcHelper.createTables(jdbcTemplate, Dialect.POSTGRESQL);
    assertLockVersionLength(jdbcTemplate, "db_lock", WIDENED_LENGTH);
  }

  private static void assertLockVersionLength(
      JdbcTemplate jdbcTemplate, String tableName, int expected) {
    Integer length =
        jdbcTemplate.queryForObject(
            "select character_maximum_length from information_schema.columns "
                + "where table_name = ? and column_name = 'lock_version'",
            Integer.class,
            tableName);
    assertEquals(expected, length, () -> "lock_version width mismatch for table " + tableName);
  }
}
