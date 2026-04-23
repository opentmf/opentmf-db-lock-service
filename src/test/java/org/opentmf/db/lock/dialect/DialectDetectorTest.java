package org.opentmf.db.lock.dialect;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.SQLException;
import java.util.Optional;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

class DialectDetectorTest {

  @ParameterizedTest
  @CsvSource({
      "'PostgreSQL 16.0',POSTGRESQL",
      "'postgresql',POSTGRESQL",
      "'MySQL',MYSQL",
      "'mysql 8.4',MYSQL",
      "'MariaDB',MYSQL",
      "'Oracle',ORACLE",
      "'Microsoft SQL Server',SQLSERVER",
      "'SQL Server',SQLSERVER",
      "'DB2/LINUXX8664',DB2",
      "'H2',H2"
  })
  void mapProductName_recognizesSupportedProducts(String product, Dialect expected) {
    assertSame(expected, DialectDetector.mapProductName(product).orElseThrow());
  }

  @ParameterizedTest
  @ValueSource(strings = {"CockroachDB", "Firebird", "Informix", "HSQLDB", ""})
  @NullSource
  void mapProductName_returnsEmptyForUnsupportedOrNullProducts(String product) {
    assertEquals(Optional.empty(), DialectDetector.mapProductName(product));
  }

  @Test
  void detect_usesConnectionMetaData() throws SQLException {
    DataSource dataSource = mock(DataSource.class);
    Connection connection = mock(Connection.class);
    DatabaseMetaData metaData = mock(DatabaseMetaData.class);
    when(dataSource.getConnection()).thenReturn(connection);
    when(connection.getMetaData()).thenReturn(metaData);
    when(metaData.getDatabaseProductName()).thenReturn("PostgreSQL 16.0");

    assertSame(Dialect.POSTGRESQL, DialectDetector.detect(dataSource));
  }

  @Test
  void detect_throwsIllegalStateWhenProductUnsupported() throws SQLException {
    DataSource dataSource = mock(DataSource.class);
    Connection connection = mock(Connection.class);
    DatabaseMetaData metaData = mock(DatabaseMetaData.class);
    when(dataSource.getConnection()).thenReturn(connection);
    when(connection.getMetaData()).thenReturn(metaData);
    when(metaData.getDatabaseProductName()).thenReturn("Sybase ASE");

    IllegalStateException ex = assertThrows(IllegalStateException.class,
        () -> DialectDetector.detect(dataSource));
    assertTrue(ex.getMessage().contains("Sybase ASE"));
    assertTrue(ex.getMessage().contains("opentmf.db-lock.dialect"));
  }

  @Test
  void detect_propagatesSqlExceptionFromGetConnection() throws SQLException {
    DataSource dataSource = mock(DataSource.class);
    doThrow(new SQLException("boom")).when(dataSource).getConnection();
    SQLException ex = assertThrows(SQLException.class, () -> DialectDetector.detect(dataSource));
    assertEquals("boom", ex.getMessage());
  }
}
