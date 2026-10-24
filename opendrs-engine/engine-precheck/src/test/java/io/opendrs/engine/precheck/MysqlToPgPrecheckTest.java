package io.opendrs.engine.precheck;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.opendrs.common.metadata.DatabaseType;
import io.opendrs.engine.precheck.fact.FactStore;
import io.opendrs.engine.precheck.profile.MysqlToPgProfile;
import io.opendrs.engine.precheck.support.FakeServer;
import io.opendrs.jdbc.JdbcConnectionConfig;
import io.opendrs.jdbc.metadata.Table;
import io.opendrs.jdbc.metadata.TableRef;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Drives the whole MySQL to PostgreSQL checklist against two in-memory endpoints.
 *
 * <p>This is the test that keeps the profile honest: it asserts the rows the wizard will show, so
 * adding a check without deciding what it should say on a healthy pair fails here.
 */
class MysqlToPgPrecheckTest {

    private static final List<Table> DEFAULT_TABLES = List.of(
            new Table(new TableRef("hr", "emp")),
            new Table(new TableRef("hr", "dept")));

    private final List<PrecheckResults> published = new ArrayList<>();
    private PrecheckResults results;
    private FakeServer source;
    private FakeServer target;

    @BeforeEach
    void setUp() {
        source = FakeServer.mysql()
                .table("hr", "emp", "InnoDB", true)
                .table("hr", "dept", "InnoDB", true);
        target = FakeServer.postgres();
        target.schemas.add("hr");
        target.schemaPrivileges.put("hr#USAGE", true);
        target.schemaPrivileges.put("hr#CREATE", true);
    }

    @Test
    void healthyPairPassesEveryCheck() {
        run();

        assertTrue(results.admitted(), () -> "blocking rows: " + results.summary());
        assertEquals(18, results.items().size());
        assertEquals(MysqlToPgProfile.ID, results.profileId());
        assertEquals(0L, results.items().stream().filter(item -> item.verdict() != Verdict.PASS).count(),
                this::describe);
    }

    @Test
    void everyRowIsPublishedAsPendingBeforeAnyCheckRuns() {
        run();

        PrecheckResults first = published.getFirst();
        assertEquals(18, first.items().size());
        assertTrue(first.items().stream().allMatch(item -> item.state() == ExecutionState.PENDING));
        assertNull(first.runningId());
        // Display order is the group order, not the execution order: 参数 first, 网络 last but two.
        assertEquals(CheckIds.SOURCE_LOG_BIN, first.items().get(0).id());
        assertEquals(CheckIds.SOURCE_CONNECTION, first.items().get(14).id());
        assertEquals(CheckIds.TARGET_TABLE_CONFLICT, first.items().get(17).id());
    }

    @Test
    void runningRowIsAnnouncedBeforeItsVerdict() {
        run();

        long announced = published.stream().filter(snapshot -> snapshot.runningId() != null).count();
        assertEquals(18L, announced);
        assertNull(results.runningId());
    }

    @Test
    void statementBinlogFormatBlocksTheRun() {
        source.parameters.put("binlog_format", "STATEMENT");
        run();

        PrecheckItem item = item(CheckIds.SOURCE_BINLOG_FORMAT);
        assertEquals(Verdict.FAIL, item.verdict());
        assertEquals(Severity.BLOCKER, item.severity());
        assertEquals("STATEMENT", item.actual());
        assertEquals("ROW", item.expected());
        assertFalse(results.admitted());
        assertTrue(results.summary().contains(CheckIds.SOURCE_BINLOG_FORMAT));
    }

    @Test
    void removedLogSlaveUpdatesIsNotApplicableRatherThanFailed() {
        source.parameters.remove("log_slave_updates");
        run();

        assertEquals(Verdict.NOT_APPLICABLE, item(CheckIds.SOURCE_LOG_SLAVE_UPDATES).verdict());
        assertTrue(results.admitted());
    }

    @Test
    void binlogExpiryFallsBackToExpireLogsDaysWhenSecondsIsGone() {
        source.parameters.remove("binlog_expire_logs_seconds");
        source.parameters.put("expire_logs_days", "7");
        run();

        PrecheckItem item = item(CheckIds.SOURCE_BINLOG_EXPIRY);
        assertEquals(Verdict.PASS, item.verdict());
        assertTrue(item.message().contains("expire_logs_days = 7"));
    }

    @Test
    void unreachableSourceFailsOneRowAndBlocksTheRestOfItsSide() {
        execute(Map.of(DatabaseType.POSTGRESQL, target), DEFAULT_TABLES);

        PrecheckItem connection = item(CheckIds.SOURCE_CONNECTION);
        assertEquals(Verdict.FAIL, connection.verdict());
        assertEquals(ExecutionState.COMPLETED, connection.state());

        PrecheckItem blocked = item(CheckIds.SOURCE_LOG_BIN);
        assertEquals(ExecutionState.BLOCKED, blocked.state());
        // The row names the check it depends on, not the raw JDBC error it inherited.
        assertTrue(blocked.message().contains("源库连接是否成功"), blocked.message());
        assertTrue(blocked.actual().contains("Connection refused"), blocked.actual());
        assertEquals(ExecutionState.BLOCKED, item(CheckIds.SOURCE_PRIMARY_KEY).state());
        // The target is still checked: a broken source must not hide a broken target.
        assertEquals(Verdict.PASS, item(CheckIds.TARGET_CONNECTION).verdict());
        assertEquals(Verdict.PASS, item(CheckIds.TARGET_INSTANCE_STATE).verdict());
        // A check that reads both ends cannot be decided.
        assertEquals(ExecutionState.BLOCKED, item(CheckIds.COMPAT_CHARSET).state());
        assertFalse(results.admitted());
    }

    @Test
    void noPrimaryKeyTablesAreReportedAndCappedAtTen() {
        List<Table> tables = new ArrayList<>();
        for (int i = 1; i <= 12; i++) {
            source.table("hr", "t" + i, "InnoDB", false);
            tables.add(new Table(new TableRef("hr", "t" + i)));
        }
        execute(Map.of(DatabaseType.MYSQL, source, DatabaseType.POSTGRESQL, target), tables);

        PrecheckItem item = item(CheckIds.SOURCE_PRIMARY_KEY);
        assertEquals(Verdict.FAIL, item.verdict());
        assertEquals(Severity.WARNING, item.severity());
        assertEquals(10, item.details().size());
        assertTrue(item.message().contains("12 张表无主键"));
        assertTrue(item.message().contains("仅展示前 10 张"));
        // A warning does not block: the user confirms it by moving to the next step.
        assertTrue(results.admitted());
    }

    @Test
    void nonInnodbTableBlocksTheRun() {
        source.table("hr", "log", "MyISAM", true);
        execute(Map.of(DatabaseType.MYSQL, source, DatabaseType.POSTGRESQL, target), List.of(
                new Table(new TableRef("hr", "emp")),
                new Table(new TableRef("hr", "log"))));

        PrecheckItem item = item(CheckIds.SOURCE_UNSUPPORTED_ENGINE);
        assertEquals(Verdict.FAIL, item.verdict());
        assertTrue(item.details().contains("hr.log（MyISAM）"));
        assertFalse(results.admitted());
    }

    @Test
    void nonUtf8TargetIsAWarningNotABlocker() {
        target.charset = "LATIN1";
        target.parameters.put("server_encoding", "LATIN1");
        run();

        PrecheckItem item = item(CheckIds.COMPAT_CHARSET);
        assertEquals(Verdict.FAIL, item.verdict());
        assertEquals(Severity.WARNING, item.severity());
        assertTrue(results.admitted());
    }

    @Test
    void existingTargetTablesAreReported() {
        target.table("hr", "emp", null, false);
        run();

        PrecheckItem item = item(CheckIds.TARGET_TABLE_CONFLICT);
        assertEquals(Verdict.FAIL, item.verdict());
        assertEquals(List.of("hr.emp"), item.details());
        assertTrue(results.admitted());
    }

    @Test
    void targetStandbyFailsInstanceState() {
        target.readOnly = true;
        run();

        assertEquals(Verdict.FAIL, item(CheckIds.TARGET_INSTANCE_STATE).verdict());
        assertFalse(results.admitted());
    }

    @Test
    void missingReplicationGrantFailsTheSourcePermission() {
        source.grants.clear();
        source.grants.add("GRANT SELECT ON `hr`.* TO `cdc`@`%`");
        run();

        PrecheckItem item = item(CheckIds.SOURCE_PERMISSION);
        assertEquals(Verdict.FAIL, item.verdict());
        assertTrue(item.actual().contains("REPLICATION SLAVE"));
        assertTrue(item.actual().contains("RELOAD"));
        // SELECT was granted on the schema that is actually selected.
        assertFalse(item.actual().contains("SELECT"));
    }

    @Test
    void wrongTimezoneOffsetFailsAsWarning() {
        target.utcOffsetSeconds = 0;
        run();

        PrecheckItem item = item(CheckIds.COMPAT_TIMEZONE);
        assertEquals(Verdict.FAIL, item.verdict());
        assertTrue(item.actual().contains("+00:00"));
        assertTrue(results.admitted());
    }

    @Test
    void oldServerVersionBlocksBothSides() {
        source.version = "5.7.44-log";
        target.version = "15.4";
        run();

        assertEquals(Verdict.FAIL, item(CheckIds.SOURCE_VERSION).verdict());
        assertEquals(Verdict.FAIL, item(CheckIds.TARGET_VERSION).verdict());
        assertFalse(results.admitted());
    }

    private void run() {
        execute(Map.of(DatabaseType.MYSQL, source, DatabaseType.POSTGRESQL, target), DEFAULT_TABLES);
    }

    private void execute(Map<DatabaseType, FakeServer> servers, List<Table> tables) {
        PrecheckContext context = new PrecheckContext(
                config(DatabaseType.MYSQL, "10.0.0.2", 3306, "hr"),
                config(DatabaseType.POSTGRESQL, "10.0.0.3", 5432, "appdb"),
                tables,
                tables,
                true,
                false);
        PrecheckPlan plan = new PlanBuilder(MysqlToPgProfile.registry()).build(new MysqlToPgProfile(), context);
        try (FactStore facts = new FactStore(context, FakeServer.factory(servers))) {
            results = new PrecheckRunner(plan, context, facts, published::add).run();
        }
    }

    private PrecheckItem item(String id) {
        PrecheckItem item = results.item(id).orElse(null);
        assertNotNull(item, () -> "missing check " + id + " in " + describe());
        return item;
    }

    private String describe() {
        return results.items().stream()
                .map(item -> item.id() + "=" + item.state() + "/" + item.verdict())
                .toList()
                .toString();
    }

    private static JdbcConnectionConfig config(DatabaseType type, String host, int port, String database) {
        return JdbcConnectionConfig.builder()
                .type(type)
                .host(host)
                .port(port)
                .database(database)
                .username("cdr")
                .password("secret")
                .build();
    }
}
