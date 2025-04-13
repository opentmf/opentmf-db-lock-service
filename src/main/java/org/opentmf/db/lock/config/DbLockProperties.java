package org.opentmf.db.lock.config;

import org.opentmf.db.lock.model.LockType;
import jakarta.validation.Valid;
import java.util.EnumMap;
import java.util.Map;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @author Gokhan Demir
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "opentmf.db-lock", ignoreUnknownFields = false)
public class DbLockProperties extends DurationProperties {

  /**
   * If <strong>true</strong>, the required tables will be created by the db lock service in the
   * auto-configuration stage, if they do not already exist.
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
   * Optional duration overrides per lockType. If not specified in the configuration for a certain
   * lockType, then the defaults will apply.
   */
  @Valid
  private Map<LockType, DurationProperties> durationOverrides = new EnumMap<>(LockType.class);
}
