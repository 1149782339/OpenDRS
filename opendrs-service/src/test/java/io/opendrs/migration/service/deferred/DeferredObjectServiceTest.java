package io.opendrs.migration.service.deferred;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.opendrs.engine.common.log.MigrationLogListener;
import io.opendrs.engine.common.metadata.TableId;
import io.opendrs.engine.jdbc.JdbcConnection;
import io.opendrs.engine.jdbc.JdbcConnectionFactory;
import io.opendrs.engine.jdbc.deferred.DeferredObject;
import io.opendrs.engine.jdbc.deferred.DeferredObjectType;
import io.opendrs.engine.jdbc.deferred.MySqlObjectCatalog;
import io.opendrs.engine.jdbc.deferred.TargetObjectCatalog;
import io.opendrs.engine.replay.mapping.MappingValidator;
import io.opendrs.migration.api.request.MigrationOptions.TargetTablePolicy;
import io.opendrs.migration.domain.ConnectionInfo;
import io.opendrs.migration.domain.DbType;
import io.opendrs.migration.domain.DeferredGate;
import io.opendrs.migration.domain.DeferredObjectRecord;
import io.opendrs.migration.domain.DeferredObjectState;
import io.opendrs.migration.domain.TaskPipeline;
import io.opendrs.migration.mapper.DeferredObjectMapper;
import io.opendrs.migration.mapper.TaskPipelineMapper;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Ordering and state transitions of the deferred objects against a fake target: rows recorded
 * before anything is dropped, foreign keys dropped before indexes, capture only once, the target
 * table policy, and the rebuild's REBUILT / FAILED bookkeeping.
 */
class DeferredObjectServiceTest {

    private static final long PIPELINE = 7L;
    private static final TableId ORDERS = TableId.of("shop", "orders");

    private DeferredObjectMapper objectMapper;
    private TaskPipelineMapper pipelineMapper;
    private JdbcConnection connection;
    private TargetObjectCatalog catalog;
    private DeferredObjectService service;
    private List<DeferredObjectRecord> rows;
    private List<String> executed;
    private TaskPipeline pipeline;

    private final DeferredObject index = new DeferredObject("shop", "orders", DeferredObjectType.INDEX, "idx_user",
            "CREATE INDEX idx_user", "DROP INDEX idx_user", null, 1);
    private final DeferredObject unique = new DeferredObject("shop", "orders", DeferredObjectType.UNIQUE, "uk_no",
            "ADD UNIQUE uk_no", "DROP UNIQUE uk_no", null, 2);
    private final DeferredObject foreignKey = new DeferredObject("shop", "orders", DeferredObjectType.FOREIGN_KEY,
            "fk_user", "ADD FK fk_user NOT VALID", "DROP FK fk_user", "VALIDATE fk_user", 3);

    @BeforeEach
    void setUp() throws Exception {
        objectMapper = mock(DeferredObjectMapper.class);
        pipelineMapper = mock(TaskPipelineMapper.class);
        connection = mock(JdbcConnection.class);
        catalog = mock(TargetObjectCatalog.class);
        JdbcConnectionFactory factory = mock(JdbcConnectionFactory.class);
        when(factory.open(any())).thenReturn(connection);
        when(connection.tableExists(anyString(), anyString())).thenReturn(true);
        when(catalog.disableForeignKeySql()).thenReturn(List.of("SET session_replication_role = replica"));
        when(catalog.lockTimeoutSql(anyInt())).thenAnswer(inv -> "SET lock_timeout = '" + inv.getArgument(0) + "s'");
        when(catalog.probeRowSql(any())).thenReturn("PROBE");
        when(catalog.truncateSql(any())).thenAnswer(inv -> "TRUNCATE " + ((TableId) inv.getArgument(0)).getTable());
        when(catalog.mergedCreate(any())).thenReturn(Optional.empty());
        executed = new ArrayList<>();
        doAnswer(inv -> {
            String sql = inv.getArgument(0);
            if (sql.startsWith("FAIL")) {
                throw new SQLException("boom " + sql);
            }
            executed.add(sql);
            return null;
        }).when(connection).execute(anyString());

        rows = new ArrayList<>();
        doAnswer(inv -> {
            DeferredObjectRecord row = inv.getArgument(0);
            row.setId((long) rows.size() + 1);
            rows.add(row);
            return 1;
        }).when(objectMapper).insertIgnore(any());
        when(objectMapper.findByPipelineId(PIPELINE)).thenAnswer(inv -> List.copyOf(rows));
        doAnswer(inv -> {
            row(inv.getArgument(0)).setState(inv.getArgument(1));
            row(inv.getArgument(0)).setLastError(inv.getArgument(2));
            return 1;
        }).when(objectMapper).updateState(anyLong(), any(), any());
        doAnswer(inv -> {
            DeferredObjectRecord row = row(inv.getArgument(0));
            row.setState(inv.getArgument(1));
            row.setLastError(inv.getArgument(2));
            row.setAttempts(row.getAttempts() + 1);
            return 1;
        }).when(objectMapper).recordAttempt(anyLong(), any(), any());

        pipeline = new TaskPipeline();
        pipeline.setId(PIPELINE);
        when(pipelineMapper.findById(PIPELINE)).thenAnswer(inv -> pipeline);
        when(pipelineMapper.compareAndSetDeferredGate(eq(PIPELINE), any(), any())).thenAnswer(inv -> {
            if (pipeline.getDeferredGate() != inv.getArgument(1)) {
                return 0;
            }
            pipeline.setDeferredGate(inv.getArgument(2));
            return 1;
        });

        TransactionTemplate tx = mock(TransactionTemplate.class);
        doAnswer(inv -> {
            ((java.util.function.Consumer<Object>) inv.getArgument(0)).accept(null);
            return null;
        }).when(tx).executeWithoutResult(any());
        service = new DeferredObjectService(objectMapper, pipelineMapper, new MappingValidator(), factory, tx);
        service.setCatalogs(type -> catalog);
        service.setLockConflictBackoffMs(0);
    }

    private DeferredObjectRecord row(long id) {
        return rows.get((int) id - 1);
    }

    @Test
    void prepareRecordsFirstThenDropsForeignKeysBeforeIndexes() throws Exception {
        when(catalog.capture(any(), any())).thenReturn(List.of(index, unique, foreignKey));
        when(catalog.exists(any(), any())).thenReturn(true);

        service.prepare(pipeline, target(), List.of(ORDERS), TargetTablePolicy.APPEND, MigrationLogListener.NONE);

        InOrder order = inOrder(objectMapper, pipelineMapper, connection);
        order.verify(objectMapper, org.mockito.Mockito.times(3)).insertIgnore(any());
        order.verify(pipelineMapper).compareAndSetDeferredGate(PIPELINE, DeferredGate.NONE, DeferredGate.CAPTURED);
        order.verify(connection).execute("DROP FK fk_user");
        order.verify(connection).execute("DROP UNIQUE uk_no");
        order.verify(connection).execute("DROP INDEX idx_user");
        assertThat(rows).extracting(DeferredObjectRecord::getState).containsOnly(DeferredObjectState.DROPPED);
        assertThat(pipeline.getDeferredGate()).isEqualTo(DeferredGate.CAPTURED);
    }

    @Test
    void secondPrepareNeverCapturesAgainAndOnlyFinishesTheDrops() throws Exception {
        when(catalog.capture(any(), any())).thenReturn(List.of(index, foreignKey));
        // First run dies after the foreign key drop: the index is still on the target.
        when(catalog.exists(any(), any())).thenReturn(true);
        doAnswer(inv -> {
            String sql = inv.getArgument(0);
            if (sql.equals("DROP INDEX idx_user")) {
                throw new SQLException("connection lost");
            }
            executed.add(sql);
            return null;
        }).when(connection).execute(anyString());
        assertThatThrownBy(() -> service.prepare(pipeline, target(), List.of(ORDERS), TargetTablePolicy.APPEND,
                MigrationLogListener.NONE)).isInstanceOf(IllegalStateException.class);

        doAnswer(inv -> {
            executed.add(inv.getArgument(0));
            return null;
        }).when(connection).execute(anyString());
        // The foreign key is gone now; the catalog says so.
        when(catalog.exists(any(), eq(foreignKey))).thenReturn(false);
        executed.clear();
        service.prepare(pipeline, target(), List.of(ORDERS), TargetTablePolicy.APPEND, MigrationLogListener.NONE);

        verify(catalog, org.mockito.Mockito.times(1)).capture(any(), any());
        assertThat(executed).containsExactly("DROP INDEX idx_user");
        assertThat(rows).extracting(DeferredObjectRecord::getState).containsOnly(DeferredObjectState.DROPPED);
    }

    @Test
    void failIfNotEmptyRefusesBeforeCapturingAnything() throws Exception {
        when(connection.queryOne(eq("PROBE"), any())).thenReturn(1);

        assertThatThrownBy(() -> service.prepare(pipeline, target(), List.of(ORDERS),
                TargetTablePolicy.FAIL_IF_NOT_EMPTY, MigrationLogListener.NONE))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("shop.orders");
        verify(catalog, never()).capture(any(), any());
        assertThat(pipeline.getDeferredGate()).isEqualTo(DeferredGate.NONE);
    }

    @Test
    void truncatePolicyTruncatesAfterTheDropsWithoutCascade() throws Exception {
        when(catalog.capture(any(), any())).thenReturn(List.of(foreignKey));
        when(catalog.exists(any(), any())).thenReturn(true);

        service.prepare(pipeline, target(), List.of(ORDERS), TargetTablePolicy.TRUNCATE, MigrationLogListener.NONE);

        assertThat(executed).containsExactly("DROP FK fk_user", "TRUNCATE orders");
    }

    @Test
    void rebuildMarksRebuiltAndFailedAndCountsAttempts() throws Exception {
        DeferredObject badUnique = new DeferredObject("shop", "orders", DeferredObjectType.UNIQUE, "uk_bad",
                "FAIL duplicate key", "DROP uk_bad", null, 4);
        rows.add(dropped(1, index));
        rows.add(dropped(2, badUnique));
        rows.add(dropped(3, foreignKey));
        when(catalog.exists(any(), any())).thenReturn(false);

        DeferredObjectService.RebuildOutcome first = service.rebuild(PIPELINE, target(),
                DeferredObjectService.AFTER_REPLAY_TYPES, 2, MigrationLogListener.NONE);

        assertThat(first.rebuilt()).isEqualTo(2);
        assertThat(first.failed()).isEqualTo(1);
        // FK enforcement off on the rebuild session; build order: index, unique, foreign key; VALIDATE after.
        assertThat(executed).containsExactly("SET session_replication_role = replica", "SET lock_timeout = '10s'",
                "CREATE INDEX idx_user", "ADD FK fk_user NOT VALID", "VALIDATE fk_user");
        assertThat(rows.get(1).getState()).isEqualTo(DeferredObjectState.FAILED);
        assertThat(rows.get(1).getLastError()).contains("boom FAIL duplicate key");
        assertThat(service.completeIfRestored(PIPELINE)).isFalse();
        assertThat(service.onlyExhaustedLeft(PIPELINE, 2)).isFalse();

        service.rebuild(PIPELINE, target(), DeferredObjectService.AFTER_REPLAY_TYPES, 2, MigrationLogListener.NONE);
        assertThat(rows.get(1).getAttempts()).isEqualTo(2);
        assertThat(service.onlyExhaustedLeft(PIPELINE, 2)).isTrue();

        // Exhausted: automatic passes leave it alone.
        executed.clear();
        service.rebuild(PIPELINE, target(), DeferredObjectService.AFTER_REPLAY_TYPES, 2, MigrationLogListener.NONE);
        assertThat(rows.get(1).getAttempts()).isEqualTo(2);
        assertThat(executed).isEmpty();
    }

    @Test
    void objectsAlreadyOnTheTargetAreMarkedWithoutBuilding() throws Exception {
        rows.add(dropped(1, index));
        when(catalog.exists(any(), any())).thenReturn(true);
        pipeline.setDeferredGate(DeferredGate.CAPTURED);

        service.rebuild(PIPELINE, target(), DeferredObjectService.BEFORE_REPLAY_TYPES, 3, MigrationLogListener.NONE);

        assertThat(executed).containsExactly("SET session_replication_role = replica", "SET lock_timeout = '10s'");
        assertThat(rows.getFirst().getState()).isEqualTo(DeferredObjectState.REBUILT);
        assertThat(service.completeIfRestored(PIPELINE)).isTrue();
        assertThat(pipeline.getDeferredGate()).isEqualTo(DeferredGate.RESTORED);
    }

    @Test
    void beforeReplayRebuildsPlainIndexesOnly() throws Exception {
        rows.add(dropped(1, index));
        rows.add(dropped(2, unique));
        rows.add(dropped(3, foreignKey));
        when(catalog.exists(any(), any())).thenReturn(false);

        service.rebuild(PIPELINE, target(), DeferredObjectService.BEFORE_REPLAY_TYPES, 3, MigrationLogListener.NONE);

        assertThat(executed).containsExactly("SET session_replication_role = replica", "SET lock_timeout = '10s'", "CREATE INDEX idx_user");
        assertThat(rows).extracting(DeferredObjectRecord::getState).containsExactly(
                DeferredObjectState.REBUILT, DeferredObjectState.DROPPED, DeferredObjectState.DROPPED);
    }

    @Test
    void mysqlIndexesOfOneTableAreBuiltInOneAlter() throws Exception {
        MySqlObjectCatalog mysql = new MySqlObjectCatalog();
        service.setCatalogs(type -> new DelegatingCatalog(mysql));
        DeferredObject a = new DeferredObject("shop", "orders", DeferredObjectType.INDEX, "a",
                "ALTER TABLE `shop`.`orders` ADD KEY `a` (`x`)", "d", null, 1);
        DeferredObject b = new DeferredObject("shop", "orders", DeferredObjectType.INDEX, "b",
                "ALTER TABLE `shop`.`orders` ADD KEY `b` (`y`)", "d", null, 2);
        rows.add(dropped(1, a));
        rows.add(dropped(2, b));
        when(connection.queryOne(anyString(), any(), any(), any(), any())).thenReturn(null);

        service.rebuild(PIPELINE, target(), DeferredObjectService.BEFORE_REPLAY_TYPES, 3, MigrationLogListener.NONE);

        assertThat(executed).containsExactly("SET FOREIGN_KEY_CHECKS = 0", "SET SESSION lock_wait_timeout = 10",
                "ALTER TABLE `shop`.`orders` ADD KEY `a` (`x`), ADD KEY `b` (`y`)");
        assertThat(rows).extracting(DeferredObjectRecord::getState).containsOnly(DeferredObjectState.REBUILT);
    }

    @Test
    void lockConflictWithReplayIsRetriedAtOnceWithoutUsingAnAttempt() throws Exception {
        rows.add(dropped(1, foreignKey));
        when(catalog.exists(any(), any())).thenReturn(false);
        SQLException deadlock = new SQLException("deadlock detected", "40P01");
        when(catalog.isLockConflict(any())).thenAnswer(inv -> "40P01".equals(((SQLException) inv.getArgument(0)).getSQLState()));
        int[] calls = {0};
        doAnswer(inv -> {
            String sql = inv.getArgument(0);
            if (sql.startsWith("ADD FK") && calls[0]++ < 2) {
                throw deadlock;
            }
            executed.add(sql);
            return null;
        }).when(connection).execute(anyString());

        DeferredObjectService.RebuildOutcome outcome = service.rebuild(PIPELINE, target(),
                DeferredObjectService.AFTER_REPLAY_TYPES, 3, MigrationLogListener.NONE);

        assertThat(outcome.rebuilt()).isEqualTo(1);
        assertThat(rows.get(0).getState()).isEqualTo(DeferredObjectState.REBUILT);
        assertThat(rows.get(0).getAttempts()).isEqualTo(1);
        assertThat(executed).containsSubsequence("ADD FK fk_user NOT VALID", "VALIDATE fk_user");
    }

    @Test
    void persistentLockConflictFailsTheAttempt() throws Exception {
        rows.add(dropped(1, foreignKey));
        when(catalog.exists(any(), any())).thenReturn(false);
        when(catalog.isLockConflict(any())).thenReturn(true);
        doAnswer(inv -> {
            String sql = inv.getArgument(0);
            if (sql.startsWith("ADD FK")) {
                throw new SQLException("lock timeout", "55P03");
            }
            return null;
        }).when(connection).execute(anyString());

        service.rebuild(PIPELINE, target(), DeferredObjectService.AFTER_REPLAY_TYPES, 3, MigrationLogListener.NONE);

        assertThat(rows.get(0).getState()).isEqualTo(DeferredObjectState.FAILED);
        assertThat(rows.get(0).getAttempts()).isEqualTo(1);
        verify(connection, org.mockito.Mockito.times(DeferredObjectService.LOCK_CONFLICT_TRIES))
                .execute("ADD FK fk_user NOT VALID");
    }

    private static DeferredObjectRecord dropped(long id, DeferredObject object) {
        DeferredObjectRecord row = DeferredObjectRecord.of(PIPELINE, object);
        row.setId(id);
        row.setState(DeferredObjectState.DROPPED);
        return row;
    }

    private static ConnectionInfo target() {
        ConnectionInfo info = new ConnectionInfo();
        info.setType(DbType.POSTGRESQL);
        info.setHost("localhost");
        info.setPort(5432);
        info.setDbName("shop");
        info.setUsername("u");
        info.setPassword("p");
        return info;
    }

    /** Real MySQL catalog with the existence probe answered "missing". */
    private record DelegatingCatalog(MySqlObjectCatalog delegate) implements TargetObjectCatalog {
        @Override
        public io.opendrs.engine.common.metadata.DatabaseType type() {
            return delegate.type();
        }

        @Override
        public List<DeferredObject> capture(JdbcConnection target, java.util.Collection<TableId> tables) {
            return List.of();
        }

        @Override
        public List<io.opendrs.engine.jdbc.deferred.ExternalReference> externalReferences(
                JdbcConnection target, java.util.Collection<TableId> tables) {
            return List.of();
        }

        @Override
        public boolean exists(JdbcConnection target, DeferredObject object) {
            return false;
        }

        @Override
        public List<String> disableForeignKeySql() {
            return delegate.disableForeignKeySql();
        }

        @Override
        public String lockTimeoutSql(int seconds) {
            return delegate.lockTimeoutSql(seconds);
        }

        @Override
        public boolean isLockConflict(SQLException ex) {
            return delegate.isLockConflict(ex);
        }

        @Override
        public String truncateSql(TableId table) {
            return delegate.truncateSql(table);
        }

        @Override
        public String analyzeSql(TableId table) {
            return delegate.analyzeSql(table);
        }

        @Override
        public String probeRowSql(TableId table) {
            return delegate.probeRowSql(table);
        }

        @Override
        public Optional<String> mergedCreate(List<DeferredObject> sameTable) {
            return delegate.mergedCreate(sameTable);
        }
    }
}
