package org.opentmf.db.lock.dialect;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;
import javax.sql.DataSource;
import lombok.Generated;

/**
 * Auto-detects the {@link Dialect} of the database behind a {@link DataSource} using the
 * JDBC {@code DatabaseMetaData.getDatabaseProductName()} value.
 *
 * <p>The mapping is intentionally permissive on substrings (e.g. {@code "MariaDB"} maps to
 * {@link Dialect#MYSQL}) because drivers identify themselves with varying capitalization and
 * suffixes across versions.
 *
 * @author Gokhan Demir
 */
public final class DialectDetector {

  @Generated
  private DialectDetector() {
  }

  /**
   * Opens a short-lived connection and maps the reported database product name to a
   * {@link Dialect}. Throws {@link IllegalStateException} when the product name is not one of
   * the supported dialects.
   */
  public static Dialect detect(DataSource dataSource) throws SQLException {
    try (Connection conn = dataSource.getConnection()) {
      String product = conn.getMetaData().getDatabaseProductName();
      return mapProductName(product)
          .orElseThrow(() -> new IllegalStateException(
              "Unsupported database product: '" + product + "'. Supported dialects: "
                  + Arrays.toString(Dialect.values())
                  + ". Set opentmf.db-lock.dialect to override, or supply a DDL file via "
                  + "opentmf.db-lock.ddl-location."));
    }
  }

  static Optional<Dialect> mapProductName(String product) {
    if (product == null) {
      return Optional.empty();
    }
    String p = product.toLowerCase(Locale.ROOT);
    if (p.contains("postgresql")) {
      return Optional.of(Dialect.POSTGRESQL);
    }
    if (p.contains("mysql") || p.contains("mariadb")) {
      return Optional.of(Dialect.MYSQL);
    }
    if (p.contains("oracle")) {
      return Optional.of(Dialect.ORACLE);
    }
    if (p.contains("sql server") || p.contains("microsoft")) {
      return Optional.of(Dialect.SQLSERVER);
    }
    if (p.contains("db2")) {
      return Optional.of(Dialect.DB2);
    }
    if (p.contains("h2")) {
      return Optional.of(Dialect.H2);
    }
    return Optional.empty();
  }
}
