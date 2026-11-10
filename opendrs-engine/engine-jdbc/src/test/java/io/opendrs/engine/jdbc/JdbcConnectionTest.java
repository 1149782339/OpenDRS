package io.opendrs.engine.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;

import io.opendrs.engine.common.metadata.DatabaseType;
import io.opendrs.engine.common.metadata.TableDefinition;
import io.opendrs.engine.common.metadata.TableId;
import java.sql.DriverManager;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class JdbcConnectionTest {

    private java.sql.Connection raw;
    private OracleConnection conn;

    @BeforeEach
    void openH2() throws Exception {
        raw = DriverManager.getConnection("jdbc:h2:mem:jdbc_" + System.nanoTime());
        try (var statement = raw.createStatement()) {
            statement.execute("CREATE SCHEMA APP");
            statement.execute("CREATE TABLE APP.EMP (ID INT PRIMARY KEY)");
            statement.execute("CREATE TABLE APP.DEPT (ID INT PRIMARY KEY)");
            statement.execute("CREATE TABLE APP.TMP_SKIP (ID INT PRIMARY KEY)");
        }
        conn = new OracleConnection(config(), cfg -> raw);
    }

    @AfterEach
    void closeH2() {
        if (conn != null) {
            conn.close();
        }
    }

    @Test
    void connectionIsOpenedLazilyAndReused() throws Exception {
        assertSame(raw, conn.connection());
        assertSame(raw, conn.connection());
    }

    @Test
    void connectionKnowsItsType() {
        assertEquals(DatabaseType.ORACLE, conn.type());
    }

    @Test
    void schemaAndTableExists() throws Exception {
        assertTrue(conn.schemaExists("APP"));
        assertTrue(conn.schemaExists("app"));
        assertFalse(conn.schemaExists("MISSING"));
        assertTrue(conn.tableExists("APP", "EMP"));
        assertTrue(conn.tableExists("app", "emp"));
        assertFalse(conn.tableExists("APP", "NOPE"));
        assertFalse(conn.tableExists("MISSING", "EMP"));
    }

    @Test
    void listTablesExcludesExactRefOnly() throws Exception {
        List<TableId> tables = conn.listTables("APP", List.of(TableId.of("APP", "TMP_SKIP")));
        Set<String> names = tables.stream().map(TableId::getTable).collect(Collectors.toSet());
        assertTrue(names.contains("EMP"));
        assertTrue(names.contains("DEPT"));
        assertFalse(names.contains("TMP_SKIP"));
        assertEquals(2, tables.size());
    }

    @Test
    void listTablesWithoutExcludesReturnsAllUserTables() throws Exception {
        List<TableId> tables = conn.listTables("APP", List.of());
        Set<String> names = tables.stream().map(TableId::getTable).collect(Collectors.toSet());
        assertEquals(Set.of("EMP", "DEPT", "TMP_SKIP"), names);
    }


    @Test
    void getTablesIsUnsupportedOnOracle() {
        assertThrows(UnsupportedOperationException.class, () -> conn.getTables("APP"));
    }

    @Test
    void getTablesReturnsColumnsAndPrimaryKeys() throws java.sql.SQLException {
        JdbcConnection probe = new JdbcConnection(DatabaseType.ORACLE, config(), cfg -> raw) {
            @Override
            public List<TableDefinition> getTables(String schema) throws java.sql.SQLException {
                if (schema == null || schema.isBlank()) {
                    return List.of();
                }
                List<TableId> ids = listTables(schema, List.of());
                List<TableDefinition> tables = new java.util.ArrayList<>(ids.size());
                for (TableId id : ids) {
                    tables.add(readTableDefinition(id));
                }
                return tables;
            }
        };
        try {
            assertEquals(List.of(), probe.getTables(null));
            assertEquals(List.of(), probe.getTables(" "));
            List<TableDefinition> tables = probe.getTables("APP");
            assertEquals(3, tables.size());
            TableDefinition emp = tables.stream()
                    .filter(t -> "EMP".equals(t.getTableId().getTable()))
                    .findFirst()
                    .orElseThrow();
            assertEquals(List.of("ID"), emp.getPrimaryKeyColumNames());
            assertEquals(1, emp.getColumnDefinitions().size());
            assertEquals("ID", emp.getColumnDefinitions().getFirst().getName());
            assertEquals(1, emp.getColumnDefinitions().getFirst().getPosition());
        } finally {
            // do not close: shares raw with conn fixture
        }
    }

    @Test
    void genericConnectionDoesNotImplementPrivilege() {
        OracleConnection fake = mock(OracleConnection.class, CALLS_REAL_METHODS);

        assertThrows(UnsupportedOperationException.class, () -> fake.hasSchemaPrivilege("HR"));
    }

    private static JdbcConnectionConfig config() {
        return JdbcConnectionConfig.builder()
                .type(DatabaseType.ORACLE)
                .host("localhost")
                .port(1521)
                .database("x")
                .username("sa")
                .password("")
                .build();
    }
}
