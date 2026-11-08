package io.opendrs.jdbc;

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

class MySqlConnectionTest {

    @Test
    void globalCreateIsTrue() throws Exception {
        MySqlConnection conn = fake();
        doReturn("CREATE").when(conn).queryOne(eq(MySqlConnection.GLOBAL_CREATE_SQL), any());

        assertTrue(conn.hasSchemaPrivilege("hr"));
        verify(conn, never()).queryList(anyString(), any(), any());
    }

    @Test
    void schemaLevelCreateOnThatDatabaseIsTrue() throws Exception {
        MySqlConnection conn = fake();
        doReturn(null).when(conn).queryOne(eq(MySqlConnection.GLOBAL_CREATE_SQL), any());
        doReturn(List.of("CREATE"))
                .when(conn).queryList(eq(MySqlConnection.SCHEMA_CREATE_SQL), any(), eq("hr"));

        assertTrue(conn.hasSchemaPrivilege("hr"));
    }

    @Test
    void neitherGlobalNorSchemaCreateIsFalse() throws Exception {
        MySqlConnection conn = fake();
        doReturn(null).when(conn).queryOne(eq(MySqlConnection.GLOBAL_CREATE_SQL), any());
        doReturn(List.of())
                .when(conn).queryList(eq(MySqlConnection.SCHEMA_CREATE_SQL), any(), eq("hr"));

        assertFalse(conn.hasSchemaPrivilege("hr"));
    }

    @Test
    void otherDatabaseSchemaPrivilegeIsFalse() throws Exception {
        MySqlConnection conn = fake();
        doReturn(null).when(conn).queryOne(eq(MySqlConnection.GLOBAL_CREATE_SQL), any());
        doReturn(List.of())
                .when(conn).queryList(eq(MySqlConnection.SCHEMA_CREATE_SQL), any(), eq("hr"));
        doReturn(List.of("CREATE"))
                .when(conn).queryList(eq(MySqlConnection.SCHEMA_CREATE_SQL), any(), eq("other"));

        assertFalse(conn.hasSchemaPrivilege("hr"));
    }

    @Test
    void queryFailureIsFalse() throws Exception {
        MySqlConnection conn = fake();
        doThrow(new SQLException("denied"))
                .when(conn).queryOne(eq(MySqlConnection.GLOBAL_CREATE_SQL), any());

        assertFalse(conn.hasSchemaPrivilege("hr"));
    }

    @Test
    void blankSchemaIsFalse() throws Exception {
        MySqlConnection conn = fake();

        assertFalse(conn.hasSchemaPrivilege("  "));
        verify(conn, never()).queryOne(anyString(), any());
        verify(conn, never()).queryList(anyString(), any());
    }

    @Test
    void listSchemasDropsBuiltInDatabasesAndSorts() throws Exception {
        MySqlConnection conn = fake();
        doReturn(List.of("mysql", "test", "information_schema", "app", "performance_schema", "sys"))
                .when(conn).queryList(eq("SHOW DATABASES"), any());

        assertEquals(List.of("app", "test"), conn.listSchemas());
    }

    @Test
    void listSchemasTreatsBuiltInsCaseInsensitively() throws Exception {
        MySqlConnection conn = fake();
        doReturn(List.of("Mysql", "SYS", "shop")).when(conn).queryList(eq("SHOW DATABASES"), any());

        assertEquals(List.of("shop"), conn.listSchemas());
    }

    private static MySqlConnection fake() {
        return mock(MySqlConnection.class, CALLS_REAL_METHODS);
    }
}
