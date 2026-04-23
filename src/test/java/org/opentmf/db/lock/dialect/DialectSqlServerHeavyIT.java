package org.opentmf.db.lock.dialect;

import javax.sql.DataSource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.MSSQLServerContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
class DialectSqlServerHeavyIT extends AbstractDialectIT {

  @Container
  static final MSSQLServerContainer<?> CONTAINER =
      new MSSQLServerContainer<>(
          DockerImageName.parse("mcr.microsoft.com/mssql/server:2022-latest"))
          .acceptLicense();

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
    return Dialect.SQLSERVER;
  }
}
