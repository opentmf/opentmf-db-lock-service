package org.opentmf.db.lock.dialect;

import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class DialectMySqlIT extends AbstractDialectIT {

  @Container
  static final MySQLContainer<?> CONTAINER =
      new MySQLContainer<>("mysql:8.4")
          .withUrlParam("serverTimezone", "UTC");

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
    return Dialect.MYSQL;
  }

  /**
   * The driver reading the server's {@code TIMESTAMP} values in a time zone other than the
   * server's — the JVM's zone, by default, on a server running UTC — shifts every stored instant by
   * the offset between the two. Here the driver assumes UTC+5 for a UTC server.
   */
  @Test
  void aLockWithinItsHoldTimeoutIsNotReclaimed_whenTheDriverAssumesAnotherTimeZone()
      throws Exception {
    assertALockWithinItsHoldTimeoutIsNotReclaimed(dataSourceAssumingAnotherTimeZone());
  }

  @Test
  void aRecentlyRecordedVersionDoesNotYetAllowADowngrade_whenTheDriverAssumesAnotherTimeZone()
      throws Exception {
    assertARecentlyRecordedVersionDoesNotYetAllowADowngrade(dataSourceAssumingAnotherTimeZone());
  }

  private DataSource dataSourceAssumingAnotherTimeZone() {
    DriverManagerDataSource ds = (DriverManagerDataSource) dataSource();
    ds.setUrl(CONTAINER.getJdbcUrl().replace("serverTimezone=UTC", "serverTimezone=Asia/Karachi"));
    return ds;
  }
}
