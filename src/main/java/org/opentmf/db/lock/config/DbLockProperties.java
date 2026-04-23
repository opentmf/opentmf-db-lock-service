package org.opentmf.db.lock.config;

import org.opentmf.db.lock.dialect.Dialect;
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
   * The library ships DDL for the following dialects:
   * <ul>
   *   <li>PostgreSQL</li>
   *   <li>MySQL / MariaDB</li>
   *   <li>Oracle (12c+)</li>
   *   <li>Microsoft SQL Server (2016+)</li>
   *   <li>IBM DB2 LUW (11.5+)</li>
   *   <li>H2</li>
   * </ul>
   * The active dialect is auto-detected from the JDBC connection metadata. Use
   * {@link #dialect} to pin it explicitly, or {@link #ddlLocation} to point at a
   * user-supplied script (for example, if you deploy against a database the library does not
   * ship DDL for).
   * </p>
   * <p>
   * You might want to set this to <strong>false</strong> in either of the following cases:
   * <ul>
   *   <li>If you don't want to use the db lock service in test scope for a certain Spring
   *   profile.</li>
   *   <li>If you want to use the service but opt to create the required tables yourself, for
   *   example using Liquibase or Flyway scripts within your microservice.</li>
   * </ul>
   * </p>
   */
  private boolean createTables = true;

  /**
   * Optional explicit SQL dialect. When {@code null}, the dialect is auto-detected from the
   * JDBC connection metadata. Set this when auto-detection picks the wrong answer (e.g. drivers
   * that identify themselves ambiguously such as Aurora-PostgreSQL clones) or when you want to
   * pin the value for deterministic startup.
   * <p>
   * Ignored when {@link #ddlLocation} is set — an explicit DDL file always takes precedence.
   * </p>
   */
  private Dialect dialect;

  /**
   * Optional classpath or filesystem location of a user-supplied DDL script. When set, this
   * takes precedence over both {@link #dialect} and auto-detection and the script is executed
   * verbatim during auto-configuration (subject to {@link #createTables}).
   * <p>
   * Useful when deploying against a database the library does not ship DDL for, while still
   * benefiting from {@code createTables=true} semantics. The value is resolved as a Spring
   * {@link org.springframework.core.io.Resource} location string and therefore supports
   * prefixes like {@code classpath:} and {@code file:}.
   * </p>
   */
  private String ddlLocation;

  /**
   * Optional duration overrides per lockType. If not specified in the configuration for a certain
   * lockType, then the defaults will apply.
   */
  @Valid
  private Map<LockType, DurationProperties> durationOverrides = new EnumMap<>(LockType.class);
}
