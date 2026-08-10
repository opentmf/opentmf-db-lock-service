package org.opentmf.db.lock.dialect;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Locale;
import javax.sql.DataSource;
import lombok.Generated;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.util.Assert;

/**
 * Widens {@code lock_version} from the pre-2.0.0 {@code VARCHAR(10)} definition to the
 * {@code VARCHAR(50)} the current DDL declares.
 *
 * <p>This is deliberately Java rather than an {@code ALTER} in {@code db/postgresql.sql}. An
 * unconditional {@code ALTER} in the script runs on every application start and takes an
 * {@code ACCESS EXCLUSIVE} table lock even when the column is already 50 wide, which can form a
 * lock cycle and surface as a startup deadlock when several application contexts share one
 * database and boot together. Guarding it inside the script would require a PL/pgSQL {@code DO}
 * block, which adds a plpgsql dependency the plain DDL does not have and forces the whole script
 * to be executed as one un-splittable batch.
 *
 * <p>Two properties fall out of reading the width through {@link DatabaseMetaData} instead of
 * {@code information_schema}:
 *
 * <ul>
 *   <li>The lookup is naturally scoped to the connection's own catalog and schema, so a same-named
 *       legacy table belonging to another tenant in the same database cannot trigger a widening of
 *       ours.
 *   <li>The comparison is a plain {@code < 50}. A column an operator deliberately widened past 50
 *       is left alone rather than narrowed back — narrowing would abort startup as soon as a
 *       stored {@code lock_version} exceeded 50 characters.
 * </ul>
 *
 * <p>Only PostgreSQL is migrated: it is the sole dialect with a pre-2.0.0 {@code VARCHAR(10)} era.
 * The DDL for every other dialect shipped in 2.2.0 already declaring {@code VARCHAR(50)}.
 *
 * @author Gokhan Demir
 */
@Slf4j
public final class LockVersionMigration {

  private static final int REQUIRED_LENGTH = 50;
  private static final String COLUMN = "lock_version";

  /**
   * The tables carrying a {@code lock_version} column, each paired with its complete widening
   * statement. The statements are written out as literals rather than assembled at runtime: no
   * caller-supplied value goes anywhere near this SQL, and spelling it out keeps that true by
   * construction instead of by argument. The 50 in each literal is {@link #REQUIRED_LENGTH};
   * the two are checked against each other by {@code lockVersionWidening_*} in DialectPostgresIT.
   */
  private static final List<WideningTarget> TARGETS = List.of(
      new WideningTarget("DB_LOCK",
          "alter table DB_LOCK alter column lock_version type VARCHAR(50)"),
      new WideningTarget("DB_LOCK_HISTORY",
          "alter table DB_LOCK_HISTORY alter column lock_version type VARCHAR(50)"),
      new WideningTarget("DB_LOCK_LATEST",
          "alter table DB_LOCK_LATEST alter column lock_version type VARCHAR(50)"));

  private record WideningTarget(String table, String widenSql) {
  }

  @Generated
  private LockVersionMigration() {
  }

  /**
   * Widens any {@code lock_version} column still narrower than 50 characters. A no-op for every
   * dialect other than {@link Dialect#POSTGRESQL}, and a no-op on schemas that are already
   * migrated — in particular it issues no statement at all on a healthy schema, so no exclusive
   * table lock is taken on a normal startup.
   *
   * <p>Failures are logged and swallowed: the widening only matters to installs predating 2.0.0,
   * and a database that refuses the ALTER (say, a read-only role) should not prevent an otherwise
   * healthy application from starting.
   */
  public static void widenLockVersion(JdbcTemplate jdbcTemplate, Dialect dialect) {
    if (dialect != Dialect.POSTGRESQL) {
      return;
    }
    DataSource dataSource = jdbcTemplate.getDataSource();
    Assert.notNull(dataSource, "DataSource cannot be obtained during lock_version migration");
    try (Connection conn = dataSource.getConnection()) {
      for (WideningTarget target : TARGETS) {
        widenIfNarrow(conn, target);
      }
    } catch (SQLException e) {
      log.warn("Could not verify or widen the {} column; leaving the schema as-is.", COLUMN, e);
    }
  }

  private static void widenIfNarrow(Connection conn, WideningTarget target) throws SQLException {
    int width = columnWidth(conn, target.table());
    if (width <= 0 || width >= REQUIRED_LENGTH) {
      return;
    }
    log.warn("Widening {}.{} from VARCHAR({}) to VARCHAR({}) (pre-2.0.0 schema).",
        target.table(), COLUMN, width, REQUIRED_LENGTH);
    try (Statement st = conn.createStatement()) {
      st.execute(target.widenSql());
    }
  }

  /**
   * Returns the declared character width of the column, or {@code -1} when the table or column is
   * absent. Identifier case follows the same rule the rest of the library uses: Oracle / DB2 / H2
   * fold unquoted names to upper case, PostgreSQL to lower case.
   */
  private static int columnWidth(Connection conn, String table) throws SQLException {
    DatabaseMetaData metaData = conn.getMetaData();
    boolean upper = metaData.storesUpperCaseIdentifiers();
    String tableName = upper ? table.toUpperCase(Locale.ROOT) : table.toLowerCase(Locale.ROOT);
    String columnName = upper ? COLUMN.toUpperCase(Locale.ROOT) : COLUMN.toLowerCase(Locale.ROOT);
    try (ResultSet rs =
        metaData.getColumns(conn.getCatalog(), conn.getSchema(), tableName, columnName)) {
      return rs.next() ? rs.getInt("COLUMN_SIZE") : -1;
    }
  }
}
