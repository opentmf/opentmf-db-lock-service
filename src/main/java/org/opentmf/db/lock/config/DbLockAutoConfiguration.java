package org.opentmf.db.lock.config;

import org.opentmf.db.lock.dialect.Dialect;
import org.opentmf.db.lock.dialect.DialectDetector;
import org.opentmf.db.lock.service.api.DbLockService;
import org.opentmf.db.lock.service.impl.DbLockServiceImpl;
import org.opentmf.db.lock.util.JdbcHelper;
import java.sql.SQLException;
import java.util.Objects;
import javax.sql.DataSource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.sql.init.dependency.DatabaseInitializationDependencyConfigurer;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.Resource;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * @author Gokhan Demir
 */
@AutoConfiguration(afterName = "org.springframework.boot.liquibase.autoconfigure.LiquibaseAutoConfiguration")
@ConditionalOnClass({DataSource.class})
@ConditionalOnProperty(name = "spring.datasource.url")
@EnableConfigurationProperties(DbLockProperties.class)
@EnableAspectJAutoProxy
@ComponentScan(basePackages = "org.opentmf.db.lock.annotation")
@Import(DatabaseInitializationDependencyConfigurer.class)
@Slf4j
public class DbLockAutoConfiguration {

  private final JdbcTemplate jdbcTemplate;
  private final DbLockProperties dbLockProperties;
  private final ApplicationContext applicationContext;

  public DbLockAutoConfiguration(
      JdbcTemplate jdbcTemplate,
      DbLockProperties dbLockProperties,
      ApplicationContext applicationContext) {
    this.jdbcTemplate = jdbcTemplate;
    this.dbLockProperties = dbLockProperties;
    this.applicationContext = applicationContext;
    log.debug("Initializing DbLock services.");
  }

  @Bean
  public DbLockService dbLockService() throws SQLException {
    if (dbLockProperties.isCreateTables()) {
      createLockTables();
    }
    var dbLockService = new DbLockServiceImpl(jdbcTemplate, dbLockProperties);
    dbLockService.removeStaleLocks();
    return dbLockService;
  }

  private void createLockTables() throws SQLException {
    String ddlLocation = dbLockProperties.getDdlLocation();
    if (ddlLocation != null && !ddlLocation.isBlank()) {
      Resource resource = applicationContext.getResource(ddlLocation);
      log.info("Applying user-supplied lock-table DDL from {}", ddlLocation);
      JdbcHelper.createTables(jdbcTemplate, resource);
      return;
    }
    Dialect dialect = dbLockProperties.getDialect() != null
        ? dbLockProperties.getDialect()
        : DialectDetector.detect(Objects.requireNonNull(jdbcTemplate.getDataSource()));
    log.info("Applying lock-table DDL for dialect={}", dialect.getId());
    JdbcHelper.createTables(jdbcTemplate, dialect);
  }
}
