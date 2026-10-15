package org.opentmf.db.lock.dialect;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.opentmf.db.lock.config.DbLockProperties;
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
