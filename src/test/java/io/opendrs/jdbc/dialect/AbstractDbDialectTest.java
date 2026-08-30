package io.opendrs.jdbc.dialect;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.opendrs.jdbc.JdbcConnection;
import io.opendrs.jdbc.JdbcConnectionFactory;
import io.opendrs.jdbc.metadata.Table;
import io.opendrs.jdbc.metadata.TableRef;
import io.opendrs.migration.domain.DbType;
import java.sql.DriverManager;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AbstractDbDialectTest {

    private java.sql.Connection raw;
    private JdbcConnection conn;
    private final DbDialect dialect = DbDialects.of(DbType.ORACLE);

    @BeforeEach
    void openH2() throws Exception {
        raw = DriverManager.getConnection("jdbc:h2:mem:dialect_" + System.nanoTime());
        try (var statement = raw.createStatement()) {
            statement.execute("CREATE SCHEMA APP");
            statement.execute("CREATE TABLE APP.EMP (ID INT PRIMARY KEY)");
            statement.execute("CREATE TABLE APP.DEPT (ID INT PRIMARY KEY)");
            statement.execute("CREATE TABLE APP.TMP_SKIP (ID INT PRIMARY KEY)");
        }
        conn = new JdbcConnectionFactory.DriverConnection(DbType.ORACLE, raw, dialect);
    }

    @AfterEach
    void closeH2() {
        if (conn != null) {
            conn.close();
        }
    }

    @Test
    void oracleRegistryReturnsOracleDialect() {
        assertInstanceOf(OracleDialect.class, dialect);
        assertInstanceOf(AbstractDbDialect.class, dialect);
    }

    @Test
    void schemaAndTableExists() {
        assertTrue(dialect.schemaExists(conn, "APP"));
        assertTrue(dialect.schemaExists(conn, "app"));
        assertFalse(dialect.schemaExists(conn, "MISSING"));
        assertTrue(dialect.tableExists(conn, "APP", "EMP"));
        assertTrue(dialect.tableExists(conn, "app", "emp"));
        assertFalse(dialect.tableExists(conn, "APP", "NOPE"));
        assertFalse(dialect.tableExists(conn, "MISSING", "EMP"));
    }

    @Test
    void listTablesExcludesExactRefOnly() {
        List<Table> tables = dialect.listTables(conn, "APP", List.of(new TableRef("APP", "TMP_SKIP")));
        Set<String> names = tables.stream().map(table -> table.ref().table()).collect(Collectors.toSet());
        assertTrue(names.contains("EMP"));
        assertTrue(names.contains("DEPT"));
        assertFalse(names.contains("TMP_SKIP"));
        assertEquals(2, tables.size());
    }

    @Test
    void listTablesWithoutExcludesReturnsAllUserTables() {
        List<Table> tables = dialect.listTables(conn, "APP", List.of());
        Set<String> names = tables.stream().map(table -> table.ref().table()).collect(Collectors.toSet());
        assertEquals(Set.of("EMP", "DEPT", "TMP_SKIP"), names);
    }

    @Test
    void testSqlAndAfterConnectMysqlAreNoop() {
        assertEquals("SELECT 1 FROM DUAL", dialect.testSql());
        assertEquals("SELECT 1", DbDialects.of(DbType.MYSQL).testSql());
        DbDialects.of(DbType.MYSQL).afterConnect(conn, new io.opendrs.migration.domain.ConnectionInfo());
    }
}
