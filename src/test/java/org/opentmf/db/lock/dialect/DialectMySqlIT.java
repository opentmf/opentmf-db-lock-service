package org.opentmf.db.lock.dialect;

import javax.sql.DataSource;
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
}
