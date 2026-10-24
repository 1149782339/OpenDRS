package io.opendrs.jdbc.dialect;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
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

class MysqlDialectTest {

    @Test
    void registryReturnsMysqlDialectForMysql() {
        assertInstanceOf(MysqlDialect.class, DbDialects.of(DatabaseType.MYSQL));
        assertInstanceOf(AbstractDbDialect.class, DbDialects.of(DatabaseType.MYSQL));
        assertInstanceOf(OracleDialect.class, DbDialects.of(DatabaseType.ORACLE));
        assertFalse(DbDialects.of(DatabaseType.ORACLE) instanceof MysqlDialect);
    }

    @Test
    void genericDialectDoesNotImplementPrivilege() {
        JdbcConnection conn = mock(JdbcConnection.class);
        assertThrows(
                UnsupportedOperationException.class,
                () -> new OracleDialect().hasSchemaPrivilege(conn, "HR"));
    }

    @Test
    void globalCreateIsTrue() {
        JdbcConnection conn = mock(JdbcConnection.class);
        when(conn.queryOne(eq(MysqlDialect.GLOBAL_CREATE_SQL), any())).thenReturn("CREATE");

        assertTrue(new MysqlDialect().hasSchemaPrivilege(conn, "hr"));
        verify(conn, never()).queryList(anyString(), any(), any());
    }

    @Test
    void schemaLevelCreateOnThatDatabaseIsTrue() {
        JdbcConnection conn = mock(JdbcConnection.class);
        when(conn.queryOne(eq(MysqlDialect.GLOBAL_CREATE_SQL), any())).thenReturn(null);
        when(conn.queryList(eq(MysqlDialect.SCHEMA_CREATE_SQL), any(), eq("hr")))
                .thenReturn(List.of("CREATE"));

        assertTrue(new MysqlDialect().hasSchemaPrivilege(conn, "hr"));
    }

    @Test
    void neitherGlobalNorSchemaCreateIsFalse() {
        JdbcConnection conn = mock(JdbcConnection.class);
        when(conn.queryOne(eq(MysqlDialect.GLOBAL_CREATE_SQL), any())).thenReturn(null);
        when(conn.queryList(eq(MysqlDialect.SCHEMA_CREATE_SQL), any(), eq("hr"))).thenReturn(List.of());

        assertFalse(new MysqlDialect().hasSchemaPrivilege(conn, "hr"));
    }

    @Test
    void otherDatabaseSchemaPrivilegeIsFalse() {
        JdbcConnection conn = mock(JdbcConnection.class);
        when(conn.queryOne(eq(MysqlDialect.GLOBAL_CREATE_SQL), any())).thenReturn(null);
        when(conn.queryList(eq(MysqlDialect.SCHEMA_CREATE_SQL), any(), eq("hr"))).thenReturn(List.of());
        when(conn.queryList(eq(MysqlDialect.SCHEMA_CREATE_SQL), any(), eq("other")))
                .thenReturn(List.of("CREATE"));

        assertFalse(new MysqlDialect().hasSchemaPrivilege(conn, "hr"));
    }

    @Test
    void wildcardSchemaPrivilegeIsTrue() {
        JdbcConnection conn = mock(JdbcConnection.class);
        when(conn.queryOne(eq(MysqlDialect.GLOBAL_CREATE_SQL), any())).thenReturn(null);
        when(conn.queryList(eq(MysqlDialect.SCHEMA_CREATE_SQL), any(), eq("hr")))
                .thenReturn(List.of("CREATE"));

        assertTrue(new MysqlDialect().hasSchemaPrivilege(conn, "hr"));
    }

    @Test
    void queryFailureIsFalse() {
        JdbcConnection conn = mock(JdbcConnection.class);
        when(conn.queryOne(eq(MysqlDialect.GLOBAL_CREATE_SQL), any()))
                .thenThrow(new JdbcException("denied"));

        assertFalse(new MysqlDialect().hasSchemaPrivilege(conn, "hr"));
    }

    @Test
    void blankSchemaIsFalse() {
        JdbcConnection conn = mock(JdbcConnection.class);
        assertFalse(new MysqlDialect().hasSchemaPrivilege(conn, "  "));
        verify(conn, never()).queryOne(anyString(), any());
        verify(conn, never()).queryList(anyString(), any());
    }

    @Test
    void listSchemasDropsBuiltInDatabasesAndSorts() {
        JdbcConnection conn = mock(JdbcConnection.class);
        when(conn.queryList(eq("SHOW DATABASES"), any()))
                .thenReturn(List.of(
                        "mysql", "test", "information_schema", "app", "performance_schema", "sys"));

        assertEquals(List.of("app", "test"), new MysqlDialect().listSchemas(conn));
    }

    @Test
    void listSchemasTreatsBuiltInsCaseInsensitively() {
        JdbcConnection conn = mock(JdbcConnection.class);
        when(conn.queryList(eq("SHOW DATABASES"), any()))
                .thenReturn(List.of("Mysql", "SYS", "shop"));

        assertEquals(List.of("shop"), new MysqlDialect().listSchemas(conn));
    }
}
