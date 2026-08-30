package io.opendrs.precheck;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
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

class MysqlPreCheckTest {

    private JdbcConnectionFactory factory;
    private JdbcConnection conn;
    private DbDialect dialect;
    private MysqlPreCheck preCheck;
    private ConnectionInfo info;

    @BeforeEach
    void setUp() {
        factory = Mockito.mock(JdbcConnectionFactory.class);
        conn = Mockito.mock(JdbcConnection.class);
        dialect = Mockito.mock(DbDialect.class);
        when(factory.open(any(ConnectionInfo.class))).thenReturn(conn);
        preCheck = new MysqlPreCheck(factory, dialect);
        info = new ConnectionInfo();
        info.setType(DbType.MYSQL);
        info.setHost("10.0.0.2");
        info.setPort(3306);
        info.setDbName("hr");
        info.setUsername("drs");
        info.setPassword("secret");
    }

    @Test
    void validateMissingTableThrows() {
        Table table = new Table(new TableRef("hr", "missing"));
        when(dialect.schemaExists(conn, "hr")).thenReturn(true);
        when(dialect.tableExists(conn, "hr", "missing")).thenReturn(false);

        AppException ex = assertThrows(AppException.class, () -> preCheck.validate(info, List.of(table)));
        assertEquals(ErrorCode.PARAM_INVALID, ex.getCode());
        assertTrue(ex.getMessage().contains("missing"));
    }

    @Test
    void validateMissingSchemaThrows() {
        Table table = new Table(new TableRef("gone", "emp"));
        when(dialect.schemaExists(conn, "gone")).thenReturn(false);

        AppException ex = assertThrows(AppException.class, () -> preCheck.validate(info, List.of(table)));
        assertEquals(ErrorCode.PARAM_INVALID, ex.getCode());
        assertTrue(ex.getMessage().contains("gone"));
    }

    @Test
    void precheckSourceBinlogOffFails() {
        Table table = new Table(new TableRef("hr", "emp"));
        when(dialect.schemaExists(conn, "hr")).thenReturn(true);
        when(dialect.tableExists(conn, "hr", "emp")).thenReturn(true);
        when(conn.queryOne(eq("SELECT @@log_bin"), any())).thenReturn(0);
        when(conn.queryOne(eq("SELECT @@binlog_format"), any())).thenReturn("STATEMENT");
        when(conn.queryOne(eq("SELECT @@gtid_mode"), any())).thenReturn("OFF");

        List<CheckResult> results = preCheck.precheckSource(info, List.of(table));
        assertFalse(named(results, "log_bin").ok());
        assertFalse(named(results, "binlog_format").ok());
        assertTrue(named(results, "gtid_mode").ok());
        assertTrue(named(results, "table_exists").ok());
    }

    @Test
    void precheckSourceBinlogRowOk() {
        Table table = new Table(new TableRef("hr", "emp"));
        when(dialect.schemaExists(conn, "hr")).thenReturn(true);
        when(dialect.tableExists(conn, "hr", "emp")).thenReturn(true);
        when(conn.queryOne(eq("SELECT @@log_bin"), any())).thenReturn(1);
        when(conn.queryOne(eq("SELECT @@binlog_format"), any())).thenReturn("ROW");
        when(conn.queryOne(eq("SELECT @@gtid_mode"), any())).thenReturn("ON");

        List<CheckResult> results = preCheck.precheckSource(info, List.of(table));
        assertTrue(named(results, "log_bin").ok());
        assertTrue(named(results, "binlog_format").ok());
        assertTrue(named(results, "read_privilege").ok());
        assertTrue(named(results, "gtid_mode").ok());
    }

    @Test
    void precheckTargetHappyPath() {
        Table table = new Table(new TableRef("hr", "emp"));
        when(dialect.schemaExists(conn, "hr")).thenReturn(true);
        when(dialect.hasSchemaPrivilege(conn, "hr")).thenReturn(true);
        when(dialect.tableExists(conn, "hr", "emp")).thenReturn(false);

        List<CheckResult> results = preCheck.precheckTarget(info, List.of(table));
        assertTrue(named(results, "schema_exists").ok());
        assertTrue(named(results, "schema_privilege").ok());
        assertTrue(named(results, "table_absent").ok());
        assertTrue(results.stream().allMatch(CheckResult::ok));
    }

    @Test
    void precheckTargetMissingSchemaFails() {
        Table table = new Table(new TableRef("gone", "emp"));
        when(dialect.schemaExists(conn, "gone")).thenReturn(false);
        when(dialect.tableExists(conn, "gone", "emp")).thenReturn(false);

        List<CheckResult> results = preCheck.precheckTarget(info, List.of(table));
        assertFalse(named(results, "schema_exists").ok());
        assertTrue(results.stream().noneMatch(result -> "schema_privilege".equals(result.name())));
    }

    @Test
    void precheckTargetExistingTableFails() {
        Table table = new Table(new TableRef("hr", "emp"));
        when(dialect.schemaExists(conn, "hr")).thenReturn(true);
        when(dialect.hasSchemaPrivilege(conn, "hr")).thenReturn(true);
        when(dialect.tableExists(conn, "hr", "emp")).thenReturn(true);

        List<CheckResult> results = preCheck.precheckTarget(info, List.of(table));
        assertFalse(named(results, "table_absent").ok());
        assertTrue(named(results, "table_absent").message().contains("already exists"));
    }

    @Test
    void precheckTargetNoPrivilegeFails() {
        Table table = new Table(new TableRef("hr", "emp"));
        when(dialect.schemaExists(conn, "hr")).thenReturn(true);
        when(dialect.hasSchemaPrivilege(conn, "hr")).thenReturn(false);
        when(dialect.tableExists(conn, "hr", "emp")).thenReturn(false);

        List<CheckResult> results = preCheck.precheckTarget(info, List.of(table));
        assertFalse(named(results, "schema_privilege").ok());
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
    void typeIsMysql() {
        assertEquals(DbType.MYSQL, preCheck.type());
        assertTrue(new DbPreChecks(List.of(preCheck)).of(DbType.MYSQL).isPresent());
        assertTrue(new DbPreChecks(List.of(preCheck)).of(DbType.ORACLE).isEmpty());
    }

    @Test
    void readPrivilegeFailureIsCollected() {
        Table table = new Table(new TableRef("hr", "emp"));
        when(dialect.schemaExists(conn, "hr")).thenReturn(true);
        when(dialect.tableExists(conn, "hr", "emp")).thenReturn(true);
        when(conn.queryOne(eq("SELECT 1 FROM `hr`.`emp` WHERE 1=0"), any()))
                .thenThrow(AppException.of(ErrorCode.CONNECTION_TEST_FAILED, "denied"));
        when(conn.queryOne(eq("SELECT @@log_bin"), any())).thenReturn(1);
        when(conn.queryOne(eq("SELECT @@binlog_format"), any())).thenReturn("ROW");
        when(conn.queryOne(eq("SELECT @@gtid_mode"), any())).thenReturn("ON");

        List<CheckResult> results = preCheck.precheckSource(info, List.of(table));
        assertFalse(named(results, "read_privilege").ok());
    }

    @Test
    void precheckTargetFallsBackToConnectionDatabase() {
        when(dialect.schemaExists(conn, "hr")).thenReturn(true);
        when(dialect.hasSchemaPrivilege(conn, "hr")).thenReturn(true);

        List<CheckResult> results = preCheck.precheckTarget(info, List.of());
        assertTrue(named(results, "schema_exists").ok());
        assertTrue(named(results, "schema_privilege").ok());
    }

    private static CheckResult named(List<CheckResult> results, String name) {
        return results.stream()
                .filter(result -> name.equals(result.name()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Missing check " + name + " in " + results));
    }
}
