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

import io.opendrs.common.error.AppException;
import io.opendrs.common.error.ErrorCode;
import io.opendrs.jdbc.JdbcConnection;
import io.opendrs.migration.domain.ConnectionInfo;
import io.opendrs.migration.domain.DbType;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class MysqlDialectTest {

    @Test
    void registryReturnsMysqlDialectForMysql() {
        assertInstanceOf(MysqlDialect.class, DbDialects.of(DbType.MYSQL));
        assertInstanceOf(AbstractDbDialect.class, DbDialects.of(DbType.MYSQL));
        assertInstanceOf(OracleDialect.class, DbDialects.of(DbType.ORACLE));
        assertFalse(DbDialects.of(DbType.ORACLE) instanceof MysqlDialect);
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
                .thenThrow(AppException.of(ErrorCode.CONNECTION_TEST_FAILED, "denied"));

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
    void debeziumSourceFieldsMapHostPortUserPasswordDb() {
        ConnectionInfo info = new ConnectionInfo();
        info.setType(DbType.MYSQL);
        info.setHost("10.0.0.2");
        info.setPort(3306);
        info.setDbName("hr");
        info.setUsername("cdc");
        info.setPassword("secret");
        info.setExtra(Map.of("useSsl", false, "serverTimezone", "UTC"));

        Map<String, String> fields = new MysqlDialect().debeziumSourceFields(info);
        assertEquals("10.0.0.2", fields.get("database.hostname"));
        assertEquals("3306", fields.get("database.port"));
        assertEquals("cdc", fields.get("database.user"));
        assertEquals("secret", fields.get("database.password"));
        assertEquals("hr", fields.get("database.dbname"));
        assertEquals("disabled", fields.get("database.ssl.mode"));
        assertEquals("UTC", fields.get("database.connectionTimeZone"));
    }
}
