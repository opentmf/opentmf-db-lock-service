package org.opentmf.db.lock.dialect;

import com.zaxxer.hikari.HikariDataSource;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestInstance.Lifecycle;

@TestInstance(Lifecycle.PER_CLASS)
class DialectH2IT extends AbstractDialectIT {

  private static final HikariDataSource DATA_SOURCE = buildDataSource();

  @Override
  protected DataSource dataSource() {
    return DATA_SOURCE;
  }

  @Override
  protected Dialect dialect() {
    return Dialect.H2;
  }

  @AfterAll
  static void closeDataSource() {
    DATA_SOURCE.close();
  }

  private static HikariDataSource buildDataSource() {
    HikariDataSource ds = new HikariDataSource();
    ds.setDriverClassName("org.h2.Driver");
    // A pool keeps at least one connection alive, preventing the in-memory DB
    // from being torn down between JDBC calls. (H2 caches session references
    // in IN-list CHECK constraints; those become stale after DB close.)
    ds.setJdbcUrl("jdbc:h2:mem:dblock-test-" + System.nanoTime()
        + ";DB_CLOSE_DELAY=-1;MODE=REGULAR");
    ds.setUsername("sa");
    ds.setPassword("");
    ds.setMinimumIdle(1);
    ds.setMaximumPoolSize(4);
    return ds;
  }
}
