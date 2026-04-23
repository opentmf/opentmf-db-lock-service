package org.opentmf.db.lock.dialect;

import javax.sql.DataSource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.Db2Container;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
class DialectDb2HeavyIT extends AbstractDialectIT {

  @Container
  static final Db2Container CONTAINER =
      new Db2Container(DockerImageName.parse("icr.io/db2_community/db2:latest"))
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
    return Dialect.DB2;
  }
}
