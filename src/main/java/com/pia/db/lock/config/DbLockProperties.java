package com.pia.db.lock.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @author Gokhan Demir
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "solutions-hub.db-lock", ignoreUnknownFields = false)
public class DbLockProperties {

  /**
   * If <strong>true</strong>, the required tables will be created by the db lock service in the auto-configuration
   * stage, if they do not already exist.
   * <p>
   * You might want to specify <strong>false</strong> in either of the following two cases:
   * <ul>
   *   <li>If you don't want to use the db lock service in test scope for a certain Spring profile.</li>
   *   <li>if you want to use the service but opt to create the required tables yourself, for example
   *   using liquibase scripts within your microservice. Remember, the db lock service supports creating tables
   *   only for PostgreSQL. For all the other types of databases, you will need to create the required
   *   tables yourself.</li>
   * </ul>
   * </p>
   */
  private boolean createTables = true;

  /**
   * The interval in milliseconds to check if the requested type of lock already exists or not.
   */
  long lockAcquirePollInterval = 1000L;

  /**
   * The maximum duration in milliseconds to wait until the requested type of lock is available.
   */
  long lockAcquireTimeout = 1000L * 60 * 2;

  /**
   * The maximum duration in milliseconds, that an obtained lock can be hold. When this timeout is
   * reached, the lock will automatically be released.
   */
  long lockHoldTimeout = 1000L * 60 * 5;
}
