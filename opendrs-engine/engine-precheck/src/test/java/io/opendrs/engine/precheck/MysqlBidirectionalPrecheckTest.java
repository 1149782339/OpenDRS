package io.opendrs.engine.precheck;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.opendrs.common.metadata.DatabaseType;
import io.opendrs.engine.precheck.fact.FactStore;
import io.opendrs.engine.precheck.profile.MysqlBidirectionalProfile;
import io.opendrs.engine.precheck.support.FakeServer;
import io.opendrs.jdbc.metadata.Table;
import io.opendrs.jdbc.metadata.TableRef;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Drives the whole MySQL ↔ MySQL bidirectional checklist against two in-memory endpoints.
 *
 * <p>The production incident this guards against: the profile bound target-side binlog checks
 * (target.log_bin and friends) whose rules were missing from the bidirectional registry, so the
 * plan builder died with "Unknown precheck rule" before a single item was published — the UI
 * showed an empty 0% checklist. Assembling and running the full plan here keeps bindings and
 * registry in lockstep.
 */
class MysqlBidirectionalPrecheckTest {

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
        source.parameters.put("server_id", "101");
        source.parameters.put("server_uuid", "11111111-2222-3333-4444-555555555555");
        target = FakeServer.mysql()
                .table("hr", "emp", "InnoDB", true)
                .table("hr", "dept", "InnoDB", true);
        target.parameters.put("server_id", "102");
        target.parameters.put("server_uuid", "aaaaaaaabbbb-cccc-dddd-eeee-ffffffffffff");
    }

    @Test
    void healthyPairPassesEveryCheck() {
        run();

        assertTrue(results.admitted(), () -> "blocking rows: " + results.summary());
        assertEquals(21, results.items().size());
        assertEquals(MysqlBidirectionalProfile.ID, results.profileId());
        assertEquals(0L, results.items().stream().filter(item -> item.verdict() != Verdict.PASS).count(),
                () -> results.items().toString());
    }

    /** A login that cannot set gtid_next must fail loudly, naming the privilege it needs. */
    @Test
    void deniedGtidNextFailsWithNamedPrivilege() {
        target.denyGtidNext = true;
        run();

        PrecheckItem row = results.items().stream()
                .filter(item -> item.id().equals(CheckIds.TARGET_GTID_NEXT))
                .findFirst()
                .orElseThrow();
        assertEquals(Verdict.FAIL, row.verdict());
        assertTrue(row.message() != null && row.message().contains("无权设置 gtid_next 参数"),
                () -> "message was: " + row.message());
        assertTrue(results.items().stream()
                .anyMatch(item -> item.id().equals(CheckIds.SOURCE_GTID_NEXT)
                        && item.verdict() == Verdict.PASS));
        assertFalse(results.admitted());
    }

    @Test
    void planAssemblesEveryBoundRule() {
        PrecheckContext context = context(true);
        // The exact call that threw "Unknown precheck rule: target.log_bin" in production
        // when the registry fell behind the profile bindings.
        PrecheckPlan plan = new PlanBuilder(MysqlBidirectionalProfile.registry())
                .build(new MysqlBidirectionalProfile(), context);
        assertNotNull(plan);
        assertTrue(published.isEmpty(), "assembly alone must not need a fact store");
    }

    /**
     * The production incident: a one-way MySQL → MySQL task (topology stored as {@code ONE_WAY})
     * used to die at assembly time, because the plan builder was picked from the topology flag
     * while the profile was picked from the endpoint types. One-way must assemble and run —
     * just without the bidirectional-only rows.
     */
    @Test
    void oneWayMysqlPairSkipsBidirectionalOnlyChecks() {
        PrecheckContext context = context(false);
        results = run(context);

        assertTrue(results.admitted(), () -> "blocking rows: " + results.summary());
        assertEquals(15, results.items().size());
        List<String> ids = results.items().stream().map(PrecheckItem::id).toList();
        assertFalse(ids.contains(CheckIds.TARGET_LOG_BIN));
        assertFalse(ids.contains(CheckIds.COMPAT_SERVER_IDENTITY));
        assertFalse(ids.contains(CheckIds.SOURCE_GTID_NEXT));
        assertFalse(ids.contains(CheckIds.TARGET_GTID_NEXT));
        assertEquals(0L, results.items().stream().filter(item -> item.verdict() != Verdict.PASS).count(),
                () -> results.items().toString());
    }

    private void run() {
        PrecheckContext context = context(true);
        results = run(context);
    }

    /**
     * Runs the checklist against the two in-memory endpoints. Both are MySQL, so the by-type
     * FakeServer factory cannot tell them apart — route by host instead
     * (10.0.0.2 is the source, 10.0.0.3 the target).
     */
    private PrecheckResults run(PrecheckContext context) {
        PrecheckPlan plan = new PlanBuilder(MysqlBidirectionalProfile.registry())
                .build(new MysqlBidirectionalProfile(), context);
        try (FactStore facts = new FactStore(context, new io.opendrs.jdbc.JdbcConnectionFactory() {
            @Override
            public io.opendrs.jdbc.JdbcConnection open(io.opendrs.jdbc.JdbcConnectionConfig config) {
                return ("10.0.0.3".equals(config.getHost()) ? target : source).connection();
            }
        })) {
            return new PrecheckRunner(plan, context, facts, published::add).run();
        }
    }

    private static PrecheckContext context(boolean bidirectional) {
        return new PrecheckContext(
                config("10.0.0.2"),
                config("10.0.0.3"),
                DEFAULT_TABLES,
                DEFAULT_TABLES,
                true,
                bidirectional);
    }

    private static io.opendrs.jdbc.JdbcConnectionConfig config(String host) {
        return io.opendrs.jdbc.JdbcConnectionConfig.builder()
                .type(DatabaseType.MYSQL)
                .host(host)
                .port(3306)
                .database("hr")
                .username("cdc")
                .password("secret")
                .build();
    }
}
