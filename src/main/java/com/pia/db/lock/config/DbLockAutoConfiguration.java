package com.pia.db.lock.config;

import com.pia.db.lock.service.api.DbLockService;
import com.pia.db.lock.service.impl.DbLockServiceImpl;
import com.pia.db.lock.util.JdbcHelper;
import javax.sql.DataSource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.liquibase.LiquibaseAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.sql.init.dependency.DatabaseInitializationDependencyConfigurer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * @author Gokhan Demir
 */
@AutoConfiguration(after = {LiquibaseAutoConfiguration.class})
@ConditionalOnClass({DataSource.class})
@ConditionalOnProperty(name = "spring.datasource.url")
@EnableConfigurationProperties(DbLockProperties.class)
@EnableAspectJAutoProxy
@ComponentScan(basePackages = "com.pia.db.lock.annotation")
@Import(DatabaseInitializationDependencyConfigurer.class)
@Slf4j
public class DbLockAutoConfiguration {

  private final JdbcTemplate jdbcTemplate;
  private final DbLockProperties dbLockProperties;

  public DbLockAutoConfiguration(JdbcTemplate jdbcTemplate, DbLockProperties dbLockProperties) {
    this.jdbcTemplate = jdbcTemplate;
    this.dbLockProperties = dbLockProperties;
    log.debug("Initializing DbLock services.");
  }

  @Bean
  public DbLockService dbLockService() {
    if (dbLockProperties.isCreateTables()) {
      JdbcHelper.createTables(jdbcTemplate);
    }
    return new DbLockServiceImpl(jdbcTemplate, dbLockProperties);
  }
}
