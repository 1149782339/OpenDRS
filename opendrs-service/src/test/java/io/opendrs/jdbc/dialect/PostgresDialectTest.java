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

import io.opendrs.common.error.AppException;
import io.opendrs.common.error.ErrorCode;
import io.opendrs.jdbc.JdbcConnection;
import io.opendrs.migration.domain.DbType;
import java.util.List;
import org.junit.jupiter.api.Test;

class PostgresDialectTest {

    @Test
    void registryReturnsPostgresDialect() {
        assertInstanceOf(PostgresDialect.class, DbDialects.of(DbType.POSTGRESQL));
        assertInstanceOf(AbstractDbDialect.class, DbDialects.of(DbType.POSTGRESQL));
        assertEquals("SELECT 1", DbDialects.of(DbType.POSTGRESQL).testSql());
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
                .thenThrow(AppException.of(ErrorCode.CONNECTION_TEST_FAILED, "denied"));

        assertFalse(new PostgresDialect().hasSchemaPrivilege(conn, "public"));
    }

    @Test
    void blankSchemaIsFalse() {
        JdbcConnection conn = mock(JdbcConnection.class);
        assertFalse(new PostgresDialect().hasSchemaPrivilege(conn, "  "));
        verify(conn, never()).queryList(anyString(), any(), any());
    }
}
