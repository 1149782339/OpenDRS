package io.opendrs.engine.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.sql.SQLException;
import java.util.List;
import org.junit.jupiter.api.Test;

class PostgresConnectionTest {

    @Test
    void createPrivilegeIsTrue() throws Exception {
        PostgresConnection conn = fake();
        doReturn(List.of(true))
                .when(conn).queryList(eq(PostgresConnection.SCHEMA_CREATE_SQL), any(), eq("public"));

        assertTrue(conn.hasSchemaPrivilege("public"));
    }

    @Test
    void createPrivilegeFalseIsFalse() throws Exception {
        PostgresConnection conn = fake();
        doReturn(List.of(false))
                .when(conn).queryList(eq(PostgresConnection.SCHEMA_CREATE_SQL), any(), eq("public"));

        assertFalse(conn.hasSchemaPrivilege("public"));
    }

    @Test
    void emptyPrivilegeRowsAreFalse() throws Exception {
        PostgresConnection conn = fake();
        doReturn(List.of())
                .when(conn).queryList(eq(PostgresConnection.SCHEMA_CREATE_SQL), any(), eq("public"));

        assertFalse(conn.hasSchemaPrivilege("public"));
    }

    @Test
    void queryFailureIsFalse() throws Exception {
        PostgresConnection conn = fake();
        doThrow(new SQLException("denied"))
                .when(conn).queryList(eq(PostgresConnection.SCHEMA_CREATE_SQL), any(), eq("public"));

        assertFalse(conn.hasSchemaPrivilege("public"));
    }

    @Test
    void blankSchemaIsFalse() throws Exception {
        PostgresConnection conn = fake();

        assertFalse(conn.hasSchemaPrivilege("  "));
        verify(conn, never()).queryList(anyString(), any(), any());
    }

    @Test
    void listSchemasReadsPgNamespace() throws Exception {
        PostgresConnection conn = fake();
        doReturn(List.of("app", "public"))
                .when(conn).queryList(eq(PostgresConnection.LIST_SCHEMAS_SQL), any());

        assertEquals(List.of("app", "public"), conn.listSchemas());
    }

    /** pg_namespace lists every schema, so the query itself has to filter. */
    @Test
    void listSchemasQueryExcludesSystemSchemasAndUnreadableOnes() {
        String sql = PostgresConnection.LIST_SCHEMAS_SQL;

        assertTrue(sql.contains("NOT LIKE 'pg\\_%'"), sql);
        assertTrue(sql.contains("<> 'information_schema'"), sql);
        assertTrue(sql.contains("has_schema_privilege(nspname, 'USAGE')"), sql);
    }

    @Test
    void tableTypesIncludePartitionedTables() {
        PostgresConnection conn = mock(PostgresConnection.class, CALLS_REAL_METHODS);

        assertEquals(List.of("TABLE", "PARTITIONED TABLE"), List.of(conn.tableTypes()));
    }

    private static PostgresConnection fake() {
        return mock(PostgresConnection.class, CALLS_REAL_METHODS);
    }
}
