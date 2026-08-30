package io.opendrs.precheck;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.opendrs.common.error.AppException;
import io.opendrs.common.error.ErrorCode;
import io.opendrs.jdbc.JdbcConnection;
import io.opendrs.jdbc.JdbcConnectionFactory;
import io.opendrs.jdbc.dialect.DbDialect;
import io.opendrs.jdbc.metadata.Table;
import io.opendrs.jdbc.metadata.TableRef;
import io.opendrs.migration.domain.ConnectionInfo;
import io.opendrs.migration.domain.DbType;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class PostgresPreCheckTest {

    private JdbcConnectionFactory factory;
    private JdbcConnection conn;
    private DbDialect dialect;
    private PostgresPreCheck preCheck;
    private ConnectionInfo info;

    @BeforeEach
    void setUp() {
        factory = Mockito.mock(JdbcConnectionFactory.class);
        conn = Mockito.mock(JdbcConnection.class);
        dialect = Mockito.mock(DbDialect.class);
        when(factory.open(any(ConnectionInfo.class))).thenReturn(conn);
        preCheck = new PostgresPreCheck(factory, dialect);
        info = new ConnectionInfo();
        info.setType(DbType.POSTGRESQL);
        info.setHost("10.0.0.3");
        info.setPort(5432);
        info.setDbName("appdb");
        info.setUsername("drs");
        info.setPassword("secret");
    }

    @Test
    void validateMissingTableThrows() {
        Table table = new Table(new TableRef("public", "missing"));
        when(dialect.schemaExists(conn, "public")).thenReturn(true);
        when(dialect.tableExists(conn, "public", "missing")).thenReturn(false);

        AppException ex = assertThrows(AppException.class, () -> preCheck.validate(info, List.of(table)));
        assertEquals(ErrorCode.PARAM_INVALID, ex.getCode());
        assertTrue(ex.getMessage().contains("missing"));
    }

    @Test
    void precheckTargetHappyPath() {
        Table table = new Table(new TableRef("public", "emp"));
        when(dialect.schemaExists(conn, "public")).thenReturn(true);
        when(dialect.hasSchemaPrivilege(conn, "public")).thenReturn(true);
        when(dialect.tableExists(conn, "public", "emp")).thenReturn(false);

        List<CheckResult> results = preCheck.precheckTarget(info, List.of(table));
        assertTrue(named(results, "schema_exists").ok());
        assertTrue(named(results, "schema_privilege").ok());
        assertTrue(named(results, "table_absent").ok());
        assertTrue(results.stream().allMatch(CheckResult::ok));
    }

    @Test
    void precheckTargetExistingTableFails() {
        Table table = new Table(new TableRef("public", "emp"));
        when(dialect.schemaExists(conn, "public")).thenReturn(true);
        when(dialect.hasSchemaPrivilege(conn, "public")).thenReturn(true);
        when(dialect.tableExists(conn, "public", "emp")).thenReturn(true);

        List<CheckResult> results = preCheck.precheckTarget(info, List.of(table));
        assertFalse(named(results, "table_absent").ok());
        assertTrue(named(results, "table_absent").message().contains("already exists"));
    }

    @Test
    void precheckTargetNoPrivilegeFails() {
        Table table = new Table(new TableRef("public", "emp"));
        when(dialect.schemaExists(conn, "public")).thenReturn(true);
        when(dialect.hasSchemaPrivilege(conn, "public")).thenReturn(false);
        when(dialect.tableExists(conn, "public", "emp")).thenReturn(false);

        List<CheckResult> results = preCheck.precheckTarget(info, List.of(table));
        assertFalse(named(results, "schema_privilege").ok());
        assertTrue(named(results, "table_absent").ok());
    }

    @Test
    void precheckTargetMissingSchemaDoesNotFail() {
        Table table = new Table(new TableRef("app", "emp"));
        when(dialect.schemaExists(conn, "app")).thenReturn(false);
        when(dialect.tableExists(conn, "app", "emp")).thenReturn(false);

        List<CheckResult> results = preCheck.precheckTarget(info, List.of(table));
        assertTrue(named(results, "schema_exists").ok());
        assertTrue(named(results, "schema_exists").message().contains("does not exist yet"));
        assertTrue(results.stream().noneMatch(result -> "schema_privilege".equals(result.name())));
        verify(dialect, never()).hasSchemaPrivilege(conn, "app");
        assertTrue(named(results, "table_absent").ok());
    }

    @Test
    void precheckTargetConnectFailure() {
        when(factory.open(any(ConnectionInfo.class)))
                .thenThrow(AppException.of(ErrorCode.CONNECTION_TEST_FAILED, "refused"));

        List<CheckResult> results = preCheck.precheckTarget(info, List.of());
        assertFalse(named(results, "connect").ok());
        assertEquals("refused", named(results, "connect").message());
    }

    @Test
    void precheckTargetFallsBackToPublicNotDatabase() {
        when(dialect.schemaExists(conn, "public")).thenReturn(true);
        when(dialect.hasSchemaPrivilege(conn, "public")).thenReturn(true);

        List<CheckResult> results = preCheck.precheckTarget(info, List.of());
        assertTrue(named(results, "schema_exists").ok());
        assertTrue(named(results, "schema_privilege").ok());
        verify(dialect, never()).schemaExists(conn, "appdb");
    }

    @Test
    void precheckSourceReadUsesWhereFalse() {
        Table table = new Table(new TableRef("public", "emp"));
        when(dialect.schemaExists(conn, "public")).thenReturn(true);
        when(dialect.tableExists(conn, "public", "emp")).thenReturn(true);
        when(conn.queryOne(eq("SELECT 1 FROM \"public\".\"emp\" WHERE false"), any())).thenReturn(null);

        List<CheckResult> results = preCheck.precheckSource(info, List.of(table));
        assertTrue(named(results, "schema_exists").ok());
        assertTrue(named(results, "table_exists").ok());
        assertTrue(named(results, "read_privilege").ok());
        assertTrue(results.stream().noneMatch(result -> "log_bin".equals(result.name())));
    }

    @Test
    void typeIsPostgresql() {
        assertEquals(DbType.POSTGRESQL, preCheck.type());
        assertTrue(new DbPreChecks(List.of(preCheck)).of(DbType.POSTGRESQL).isPresent());
        assertTrue(new DbPreChecks(List.of(preCheck)).of(DbType.MYSQL).isEmpty());
    }

    private static CheckResult named(List<CheckResult> results, String name) {
        return results.stream()
                .filter(result -> name.equals(result.name()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Missing check " + name + " in " + results));
    }
}
