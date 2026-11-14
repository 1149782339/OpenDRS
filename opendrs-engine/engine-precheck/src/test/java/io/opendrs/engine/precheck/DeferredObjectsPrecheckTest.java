package io.opendrs.engine.precheck;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.opendrs.engine.common.metadata.DatabaseType;
import io.opendrs.engine.common.metadata.TableId;
import io.opendrs.engine.jdbc.JdbcConnectionConfig;
import io.opendrs.engine.precheck.profile.MysqlBidirectionalProfile;
import io.opendrs.engine.precheck.profile.MysqlToPgProfile;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The deferred-objects checks are bound only for one-way tasks that run the flow. */
class DeferredObjectsPrecheckTest {

    private static final List<String> DEFERRED_IDS = List.of(
            CheckIds.TARGET_TABLE_NOT_EMPTY,
            CheckIds.TARGET_EXTERNAL_FOREIGN_KEY,
            CheckIds.TARGET_FOREIGN_KEY_BYPASS,
            CheckIds.SOURCE_CASCADE_FOREIGN_KEY);

    @Test
    void mysqlToPgBindsDeferredChecksOnlyWhenRequested() {
        assertTrue(ids(pgPlan(true)).containsAll(DEFERRED_IDS));
        assertTrue(ids(pgPlan(false)).stream().noneMatch(DEFERRED_IDS::contains));
    }

    @Test
    void mysqlPairBindsDeferredChecksForOneWayOnly() {
        assertTrue(ids(mysqlPlan(false, true)).containsAll(DEFERRED_IDS));
        assertTrue(ids(mysqlPlan(true, true)).stream().noneMatch(DEFERRED_IDS::contains));
        assertFalse(ids(mysqlPlan(false, false)).contains(CheckIds.TARGET_TABLE_NOT_EMPTY));
    }

    private static PrecheckPlan pgPlan(boolean deferred) {
        return new PlanBuilder(MysqlToPgProfile.registry())
                .build(new MysqlToPgProfile(), context(DatabaseType.POSTGRESQL, false, deferred));
    }

    private static PrecheckPlan mysqlPlan(boolean bidirectional, boolean deferred) {
        return new PlanBuilder(MysqlBidirectionalProfile.registry())
                .build(new MysqlBidirectionalProfile(), context(DatabaseType.MYSQL, bidirectional, deferred));
    }

    private static List<String> ids(PrecheckPlan plan) {
        return plan.pendingItems().stream().map(PrecheckItem::id).toList();
    }

    private static PrecheckContext context(DatabaseType targetType, boolean bidirectional, boolean deferred) {
        List<TableId> tables = List.of(TableId.of("shop", "orders"));
        return new PrecheckContext(config(DatabaseType.MYSQL), config(targetType), tables, tables, true,
                bidirectional, deferred);
    }

    private static JdbcConnectionConfig config(DatabaseType type) {
        return JdbcConnectionConfig.builder().type(type).host("h").port(1).username("u").password("p").build();
    }
}
