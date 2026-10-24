package io.opendrs.jdbc.dialect;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.opendrs.common.metadata.DatabaseType;
import io.opendrs.jdbc.JdbcConnection;
import io.opendrs.jdbc.JdbcException;
import java.util.List;
import org.junit.jupiter.api.Test;

class PostgresDialectTest {

    @Test
    void registryReturnsPostgresDialect() {
        assertInstanceOf(PostgresDialect.class, DbDialects.of(DatabaseType.POSTGRESQL));
        assertInstanceOf(AbstractDbDialect.class, DbDialects.of(DatabaseType.POSTGRESQL));
        assertEquals("SELECT 1", DbDialects.of(DatabaseType.POSTGRESQL).testSql());
    }

    @Test
    void createPrivilegeIsTrue() {
        JdbcConnection conn = mock(JdbcConnection.class);
        when(conn.queryList(eq(PostgresDialect.SCHEMA_CREATE_SQL), any(), eq("public")))
                .thenReturn(List.of(true));

        assertTrue(new PostgresDialect().hasSchemaPrivilege(conn, "public"));
    }

    @Test
    void createPrivilegeFalseIsFalse() {
        JdbcConnection conn = mock(JdbcConnection.class);
        when(conn.queryList(eq(PostgresDialect.SCHEMA_CREATE_SQL), any(), eq("public")))
                .thenReturn(List.of(false));

        assertFalse(new PostgresDialect().hasSchemaPrivilege(conn, "public"));
    }

    @Test
    void emptyPrivilegeRowsAreFalse() {
        JdbcConnection conn = mock(JdbcConnection.class);
        when(conn.queryList(eq(PostgresDialect.SCHEMA_CREATE_SQL), any(), eq("public"))).thenReturn(List.of());

        assertFalse(new PostgresDialect().hasSchemaPrivilege(conn, "public"));
    }

    @Test
    void queryFailureIsFalse() {
        JdbcConnection conn = mock(JdbcConnection.class);
        when(conn.queryList(eq(PostgresDialect.SCHEMA_CREATE_SQL), any(), eq("public")))
                .thenThrow(new JdbcException("denied"));

        assertFalse(new PostgresDialect().hasSchemaPrivilege(conn, "public"));
    }

    @Test
    void blankSchemaIsFalse() {
        JdbcConnection conn = mock(JdbcConnection.class);
        assertFalse(new PostgresDialect().hasSchemaPrivilege(conn, "  "));
        verify(conn, never()).queryList(anyString(), any(), any());
    }

    @Test
    void listSchemasReadsPgNamespace() {
        JdbcConnection conn = mock(JdbcConnection.class);
        when(conn.queryList(eq(PostgresDialect.LIST_SCHEMAS_SQL), any()))
                .thenReturn(List.of("app", "public"));

        assertEquals(List.of("app", "public"), new PostgresDialect().listSchemas(conn));
    }

    /** pg_namespace lists every schema, so the query itself has to filter. */
    @Test
    void listSchemasQueryExcludesSystemSchemasAndUnreadableOnes() {
        String sql = PostgresDialect.LIST_SCHEMAS_SQL;

        assertTrue(sql.contains("NOT LIKE 'pg\\_%'"), sql);
        assertTrue(sql.contains("<> 'information_schema'"), sql);
        assertTrue(sql.contains("has_schema_privilege(nspname, 'USAGE')"), sql);
    }

    @Test
    void tableTypesIncludePartitionedTables() {
        assertEquals(
                List.of("TABLE", "PARTITIONED TABLE"),
                List.of(new PostgresDialect().tableTypes()));
    }
}
