package io.opendrs.migration.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.opendrs.engine.capture.ReplayGate;
import io.opendrs.engine.capture.transport.CdcTransportSpec;
import io.opendrs.engine.common.log.MigrationLogListener;
import io.opendrs.engine.common.metadata.TableId;
import io.opendrs.engine.core.DataCheckParam;
import io.opendrs.engine.core.DataCheckTask;
import io.opendrs.engine.core.DataMigrationParam;
import io.opendrs.engine.core.DataMigrationTask;
import io.opendrs.engine.core.DataMigrationTaskPhase;
import io.opendrs.engine.core.MigrationEngine;
import io.opendrs.engine.core.PrecheckParam;
import io.opendrs.engine.core.PrecheckTask;
import io.opendrs.engine.core.SchemaMigrationParam;
import io.opendrs.engine.core.SchemaMigrationTask;
import io.opendrs.engine.core.TaskState;
import io.opendrs.engine.jdbc.binlog.BinlogPosition;
import io.opendrs.migration.api.request.MigrationOptions;
import io.opendrs.migration.api.request.SelectedTable;
import io.opendrs.migration.config.OpenDrsTransportProperties;
import io.opendrs.migration.domain.ConnectionInfo;
import io.opendrs.migration.domain.DbType;
import io.opendrs.migration.domain.DeferredGate;
import io.opendrs.migration.domain.MigrationMode;
import io.opendrs.migration.domain.MigrationPhase;
import io.opendrs.migration.domain.MigrationTask;
import io.opendrs.migration.domain.TaskPipeline;
import io.opendrs.migration.mapper.ConnectionInfoMapper;
import io.opendrs.migration.mapper.MigrationTaskMapper;
import io.opendrs.migration.mapper.TaskPipelineMapper;
import io.opendrs.migration.service.deferred.DeferredObjectService;
import io.opendrs.migration.service.deferred.DeferredObjects;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

/**
 * The deferred-target-objects state machine of the coordinator with a fake engine and a mocked
 * {@link DeferredObjectService}: FULL_PREPARE → FULL → POST_FULL → INCREMENTAL on both transports,
 * the snapshot end watcher, the automatic retry, and the plain flow for tasks that do not defer.
 */
class MigrationCoordinatorDeferredTest {

    private static final BinlogPosition P1 = new BinlogPosition("mysql-bin.000003", 100L, null);

    private MigrationTaskMapper taskMapper;
    private ConnectionInfoMapper connectionMapper;
    private TaskPipelineMapper pipelineMapper;
    private DeferredObjectService deferredService;
    private FakeEngine engine;
    private TaskPipeline pipeline;
    private MigrationTask task;

    @BeforeEach
    void setUp() {
        taskMapper = mock(MigrationTaskMapper.class);
        connectionMapper = mock(ConnectionInfoMapper.class);
        pipelineMapper = mock(TaskPipelineMapper.class);
        deferredService = mock(DeferredObjectService.class);
        engine = new FakeEngine();
        when(connectionMapper.findById(10L)).thenReturn(connection(DbType.MYSQL));
        when(connectionMapper.findById(11L)).thenReturn(connection(DbType.POSTGRESQL));

        pipeline = new TaskPipeline();
        pipeline.setId(500L);
        pipeline.setPublicId(UUID.randomUUID().toString());
        pipeline.setSourceEndpointId(10L);
        pipeline.setTargetEndpointId(11L);
        pipeline.setCaptureOptions(Map.of());
        pipeline.setReplayOptions(Map.of());
        when(pipelineMapper.findByTaskId(anyLong())).thenAnswer(inv -> List.of(pipeline));

        when(deferredService.gateOf(500L)).thenAnswer(inv -> pipeline.getDeferredGate());
        when(deferredService.targetTables(any(), any())).thenReturn(List.of(TableId.of("shop", "orders")));
        when(deferredService.recordSnapshotEnd(eq(500L), any())).thenReturn(P1);
        when(deferredService.snapshotEnd(500L)).thenReturn(Optional.of(P1));
        when(deferredService.rebuild(anyLong(), any(), any(), anyInt(), any()))
                .thenReturn(new DeferredObjectService.RebuildOutcome(1, 0, 0));
        doAnswer(inv -> {
            pipeline.setDeferredGate(DeferredGate.CAPTURED);
            return null;
        }).when(deferredService).prepare(any(), any(), any(), any(), any());
    }

    @Test
    void directRunDropsFirstThenStopsAfterTheSnapshotRebuildsIndexesAndRestarts() throws Exception {
        stubTask(MigrationPhase.FULL_PREPARE, null);
        when(deferredService.completeIfRestored(500L)).thenReturn(false, true);
        MigrationCoordinator job = job(null);
        Thread thread = start(job);

        assertThat(engine.awaitRuns(2)).isTrue();
        assertThat(task.getPhase()).isEqualTo(MigrationPhase.INCREMENTAL);
        InOrder order = inOrder(deferredService);
        order.verify(deferredService).prepare(eq(pipeline), any(), any(),
                eq(MigrationOptions.TargetTablePolicy.FAIL_IF_NOT_EMPTY), any());
        order.verify(deferredService, org.mockito.Mockito.atLeastOnce()).recordSnapshotEnd(eq(500L), any());
        order.verify(deferredService).rebuild(eq(500L), any(), eq(DeferredObjectService.BEFORE_REPLAY_TYPES),
                anyInt(), any());
        order.verify(deferredService).analyze(any(), any(), any());

        DataMigrationParam first = engine.params.get(0);
        DataMigrationParam second = engine.params.get(1);
        assertThat(first.targetSessionSql()).containsExactly("SET session_replication_role = replica");
        assertThat(second.targetSessionSql()).containsExactly("SET session_replication_role = replica");
        assertThat(engine.handles.get(0).stopped.getCount()).isZero();
        assertThat(second.lagListener()).isInstanceOf(SnapshotEndWatcher.class);

        // Before P1: nothing; past P1: unique and foreign keys come back.
        second.lagListener().emitAppliedOffset(Map.of("file", "mysql-bin.000003", "pos", 50L), 0L);
        verify(deferredService, never()).rebuild(anyLong(), any(), eq(DeferredObjectService.AFTER_REPLAY_TYPES),
                anyInt(), any());
        second.lagListener().emitAppliedOffset(Map.of("file", "mysql-bin.000003", "pos", 150L), 0L);
        verify(deferredService, timeout(5_000)).rebuild(eq(500L), any(),
                eq(DeferredObjectService.AFTER_REPLAY_TYPES), anyInt(), any());
        verify(deferredService, timeout(5_000).times(2)).completeIfRestored(500L);

        stop(job, thread);
        assertThat(task.getState()).isEqualTo(TaskState.STOPPED);
    }

    @Test
    void kafkaRunKeepsCapturingAndOpensTheReplayGateAfterTheIndexRebuild() throws Exception {
        stubTask(MigrationPhase.FULL, CdcTransportSpec.KAFKA);
        pipeline.setDeferredGate(DeferredGate.CAPTURED);
        when(deferredService.completeIfRestored(500L)).thenReturn(false);
        MigrationCoordinator job = job(new OpenDrsTransportProperties());
        Thread thread = start(job);

        assertThat(engine.awaitRuns(1)).isTrue();
        DataMigrationParam param = engine.params.getFirst();
        assertThat(param.replayGate()).isInstanceOf(ReplayGate.Latch.class);
        assertThat(engine.gateOpenAtSignal).isFalse();
        assertThat(param.replayGate().awaitOpen(5_000)).isTrue();
        assertThat(task.getPhase()).isEqualTo(MigrationPhase.INCREMENTAL);
        InOrder order = inOrder(deferredService);
        order.verify(deferredService, org.mockito.Mockito.atLeastOnce()).recordSnapshotEnd(eq(500L), any());
        order.verify(deferredService).rebuild(eq(500L), any(), eq(DeferredObjectService.BEFORE_REPLAY_TYPES),
                anyInt(), any());
        order.verify(deferredService).analyze(any(), any(), any());
        // The engine is never restarted on KAFKA.
        Thread.sleep(200);
        assertThat(engine.params).hasSize(1);
        verify(deferredService, never()).prepare(any(), any(), any(), any(), any());

        stop(job, thread);
    }

    @Test
    void restartInPostFullFinishesTheRebuildBeforeTheEngineStarts() throws Exception {
        stubTask(MigrationPhase.POST_FULL, null);
        pipeline.setDeferredGate(DeferredGate.CAPTURED);
        when(deferredService.completeIfRestored(500L)).thenReturn(false);
        MigrationCoordinator job = job(null);
        Thread thread = start(job);

        assertThat(engine.awaitRuns(1)).isTrue();
        verify(deferredService).rebuild(eq(500L), any(), eq(DeferredObjectService.BEFORE_REPLAY_TYPES),
                anyInt(), any());
        assertThat(task.getPhase()).isEqualTo(MigrationPhase.INCREMENTAL);
        assertThat(engine.params.getFirst().lagListener()).isInstanceOf(SnapshotEndWatcher.class);

        stop(job, thread);
    }

    @Test
    void failedObjectsAreRetriedAutomatically() throws Exception {
        stubTask(MigrationPhase.INCREMENTAL, null);
        pipeline.setDeferredGate(DeferredGate.CAPTURED);
        when(deferredService.completeIfRestored(500L)).thenReturn(false, false, true);
        MigrationCoordinator job = job(null);
        job.setRestoreRetryDelayMs(10L);
        Thread thread = start(job);

        assertThat(engine.awaitRuns(1)).isTrue();
        engine.params.getFirst().lagListener().emitAppliedOffset(Map.of("file", "mysql-bin.000004", "pos", 4L), 0L);
        verify(deferredService, timeout(5_000).times(2)).rebuild(eq(500L), any(),
                eq(DeferredObjectService.AFTER_REPLAY_TYPES), anyInt(), any());
        verify(deferredService, timeout(5_000).times(3)).completeIfRestored(500L);

        stop(job, thread);
    }

    @Test
    void taskStartedBeforeTheFeatureKeepsThePlainFlow() throws Exception {
        stubTask(MigrationPhase.FULL, null);
        // gate NONE: nothing captured for this pipeline.
        MigrationCoordinator job = job(null);
        Thread thread = start(job);

        assertThat(engine.awaitRuns(1)).isTrue();
        DataMigrationParam param = engine.params.getFirst();
        assertThat(param.targetSessionSql()).isNullOrEmpty();
        assertThat(param.lagListener()).isNotInstanceOf(SnapshotEndWatcher.class);
        assertThat(task.getPhase()).isEqualTo(MigrationPhase.INCREMENTAL);
        verify(deferredService, never()).prepare(any(), any(), any(), any(), any());
        verify(deferredService, never()).recordSnapshotEnd(anyLong(), any());

        stop(job, thread);
    }

    @Test
    void onlyOneWayFullAndIncrementalFromMysqlDefers() {
        MigrationOptions oneWay = options(null);
        MigrationOptions bidirectional = new MigrationOptions(MigrationOptions.Topology.BIDIRECTIONAL,
                oneWay.selection(), oneWay.full(), oneWay.incremental(), null, null);
        var mysql = io.opendrs.engine.common.metadata.DatabaseType.MYSQL;
        var pg = io.opendrs.engine.common.metadata.DatabaseType.POSTGRESQL;
        assertThat(DeferredObjects.eligible(MigrationMode.FULL_AND_INCREMENTAL, oneWay, mysql, pg)).isTrue();
        assertThat(DeferredObjects.eligible(MigrationMode.FULL_AND_INCREMENTAL, oneWay, mysql, mysql)).isTrue();
        assertThat(DeferredObjects.eligible(MigrationMode.FULL_AND_INCREMENTAL, bidirectional, mysql, mysql)).isFalse();
        assertThat(DeferredObjects.eligible(MigrationMode.FULL_ONLY, oneWay, mysql, pg)).isFalse();
        assertThat(DeferredObjects.eligible(MigrationMode.INCREMENTAL_ONLY, oneWay, mysql, pg)).isFalse();
        assertThat(DeferredObjects.policyOf(null)).isEqualTo(MigrationOptions.TargetTablePolicy.FAIL_IF_NOT_EMPTY);
    }

    private MigrationCoordinator job(OpenDrsTransportProperties transport) {
        MigrationCoordinatorRegistry registry = new MigrationCoordinatorRegistry();
        MigrationCoordinator job = new MigrationCoordinator(task.getId(), task.getMode(), taskMapper, connectionMapper,
                registry, engine, MigrationLogListener.NONE, null, null, null, pipelineMapper, null, transport,
                deferredService);
        registry.tryRegister(job);
        return job;
    }

    private static Thread start(MigrationCoordinator job) {
        Thread thread = new Thread(job, "deferred-job");
        thread.start();
        return thread;
    }

    private void stop(MigrationCoordinator job, Thread thread) throws InterruptedException {
        task.setState(TaskState.STOPPING);
        job.requestStop();
        thread.join(5_000);
        assertThat(thread.isAlive()).isFalse();
    }

    private void stubTask(MigrationPhase phase, CdcTransportSpec transport) {
        task = new MigrationTask();
        task.setId(42L);
        task.setPublicId(UUID.randomUUID().toString());
        task.setMode(MigrationMode.FULL_AND_INCREMENTAL);
        task.setPhase(phase);
        task.setState(TaskState.STARTING);
        task.setSourceConnectionId(10L);
        task.setTargetConnectionId(11L);
        task.setOptionsJson(options(transport));
        when(taskMapper.findById(42L)).thenAnswer(inv -> task);
        when(taskMapper.compareAndSetState(eq(42L), any(), any())).thenAnswer(inv -> {
            if (task.getState() != inv.getArgument(1)) {
                return 0;
            }
            task.setState(inv.getArgument(2));
            return 1;
        });
        when(taskMapper.compareAndSetPhase(eq(42L), any(), any())).thenAnswer(inv -> {
            if (task.getPhase() != inv.getArgument(1)) {
                return 0;
            }
            task.setPhase(inv.getArgument(2));
            return 1;
        });
        when(taskMapper.landPausing(42L)).thenAnswer(inv -> {
            task.setState(TaskState.STOPPED);
            return 1;
        });
    }

    private static MigrationOptions options(CdcTransportSpec transport) {
        return new MigrationOptions(
                MigrationOptions.Topology.ONE_WAY,
                MigrationOptions.Selection.ofTables(List.of(new SelectedTable("shop", "orders"))),
                MigrationOptions.FullSettings.defaults(),
                MigrationOptions.IncrementalSettings.defaults(),
                null,
                transport);
    }

    private static ConnectionInfo connection(DbType type) {
        ConnectionInfo info = new ConnectionInfo();
        info.setType(type);
        info.setHost("localhost");
        info.setPort(type == DbType.MYSQL ? 3306 : 5432);
        info.setDbName("shop");
        info.setUsername("u");
        info.setPassword("p");
        return info;
    }

    /**
     * First data run signals the end of the snapshot right away (as a run whose snapshot is
     * small would); later runs only stream. Every handle blocks until it is stopped.
     */
    static final class FakeEngine implements MigrationEngine {

        final List<DataMigrationParam> params = new CopyOnWriteArrayList<>();
        final List<Handle> handles = new CopyOnWriteArrayList<>();
        final CountDownLatch[] runs = {new CountDownLatch(1), new CountDownLatch(2)};
        volatile Boolean gateOpenAtSignal;

        boolean awaitRuns(int count) throws InterruptedException {
            return runs[count - 1].await(5, TimeUnit.SECONDS);
        }

        @Override
        public DataMigrationTask migrateData(DataMigrationParam param) {
            params.add(param);
            Handle handle = new Handle();
            handles.add(handle);
            param.phaseListener().accept(DataMigrationTaskPhase.FULL);
            if (params.size() == 1) {
                if (param.replayGate() instanceof ReplayGate.Latch latch) {
                    gateOpenAtSignal = latch.isOpen();
                }
                param.phaseListener().accept(DataMigrationTaskPhase.INCREMENTAL);
            }
            for (CountDownLatch run : runs) {
                run.countDown();
            }
            return handle;
        }

        @Override
        public DataCheckTask datacheck(DataCheckParam param) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean supportsPrecheck(io.opendrs.engine.common.metadata.DatabaseType source,
                io.opendrs.engine.common.metadata.DatabaseType target) {
            return false;
        }

        @Override
        public SchemaMigrationTask migrateSchema(SchemaMigrationParam param) {
            throw new UnsupportedOperationException();
        }

        @Override
        public PrecheckTask precheck(PrecheckParam param) {
            throw new UnsupportedOperationException();
        }
    }

    static final class Handle implements DataMigrationTask {

        final CountDownLatch stopped = new CountDownLatch(1);

        @Override
        public DataMigrationTaskPhase taskPhase() {
            return DataMigrationTaskPhase.INCREMENTAL;
        }

        @Override
        public TaskState state() {
            return TaskState.RUNNING;
        }

        @Override
        public Throwable failure() {
            return null;
        }

        @Override
        public void requestStop() {
            stopped.countDown();
        }

        @Override
        public void await() throws InterruptedException {
            stopped.await(10, TimeUnit.SECONDS);
        }
    }
}
