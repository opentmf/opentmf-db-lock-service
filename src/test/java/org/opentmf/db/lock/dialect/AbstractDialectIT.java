package org.opentmf.db.lock.dialect;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.opentmf.db.lock.config.DbLockProperties;
import org.opentmf.db.lock.model.AcquiredLock;
import org.opentmf.db.lock.model.LockType;
import org.opentmf.db.lock.service.impl.DbLockServiceImpl;
import org.opentmf.db.lock.util.JdbcHelper;
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

  protected abstract DataSource dataSource();

  protected abstract Dialect dialect();

  @Test
  void ddlIsIdempotentAndLockRoundTripPopulatesAllTables() throws Exception {
    JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource());

    JdbcHelper.createTables(jdbcTemplate, dialect());
    JdbcHelper.createTables(jdbcTemplate, dialect());

    DbLockProperties props = new DbLockProperties();
    props.setLockAcquirePollInterval(100L);
    props.setLockAcquireTimeout(1000L);
    props.setLockHoldTimeout(60_000L);
    DbLockServiceImpl service = new DbLockServiceImpl(jdbcTemplate, props);

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

  private static int countRows(JdbcTemplate jdbcTemplate, String table) {
    Integer n = jdbcTemplate.queryForObject("select count(*) from " + table, Integer.class);
    return n == null ? 0 : n;
  }
}
