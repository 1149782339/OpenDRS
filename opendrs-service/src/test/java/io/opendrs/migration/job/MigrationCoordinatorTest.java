package io.opendrs.migration.job;

import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.opendrs.common.metadata.DatabaseType;
import io.opendrs.engine.core.DataMigrationParam;
import io.opendrs.engine.core.DataMigrationTask;
import io.opendrs.engine.core.DataMigrationTaskPhase;
import io.opendrs.engine.core.MigrationEngine;
import io.opendrs.engine.core.PrecheckParam;
import io.opendrs.engine.core.PrecheckTask;
import io.opendrs.engine.core.SchemaMigrationParam;
import io.opendrs.engine.core.SchemaMigrationTask;
import io.opendrs.engine.core.TaskState;
import io.opendrs.jdbc.metadata.Table;
import io.opendrs.migration.api.request.MigrationOptions;
import io.opendrs.migration.api.request.SelectedTable;
import io.opendrs.migration.domain.ConnectionInfo;
import io.opendrs.migration.domain.DbType;
import io.opendrs.migration.domain.MigrationPhase;
import io.opendrs.migration.domain.MigrationMode;
import io.opendrs.migration.domain.MigrationTask;
import io.opendrs.migration.mapper.ConnectionInfoMapper;
import io.opendrs.migration.mapper.MigrationTaskMapper;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * Exercises the coordinator only: the engine is faked as two handles, one per run. The assertions
 * are about the persisted state machine — which engine call happens in which phase, and what is
 * written when it fails.
 */
class MigrationCoordinatorTest {

    private MigrationTaskMapper taskMapper;
    private ConnectionInfoMapper connectionMapper;
    private MigrationCoordinatorRegistry registry;
    private FakeMigrationEngine engine;

    @BeforeEach
    void setUp() {
        taskMapper = Mockito.mock(MigrationTaskMapper.class);
        connectionMapper = Mockito.mock(ConnectionInfoMapper.class);
        registry = new MigrationCoordinatorRegistry();
        engine = new FakeMigrationEngine();
        when(connectionMapper.findById(anyLong())).thenReturn(mysql());
        when(taskMapper.compareAndSetState(anyLong(), any(), any())).thenReturn(1);
        when(taskMapper.compareAndSetPhase(anyLong(), any(), any())).thenReturn(1);
        when(taskMapper.markFailed(anyLong(), any())).thenReturn(1);
    }

    @Test
    void schemaRunsFirstThenDataUntilStop() throws Exception {
        MigrationTask task = task(1L, MigrationPhase.PRECHECKED, TaskState.STARTING, MigrationMode.FULL_AND_INCREMENTAL);
        stubTask(task);
        MigrationCoordinator job = job(task);
        registry.tryRegister(job);

        Thread thread = new Thread(job, "test-job-1");
        thread.start();
        assertThat(engine.run.started.await(5, TimeUnit.SECONDS)).isTrue();

        assertThat(engine.schemaCount.get()).isEqualTo(1);
        assertThat(engine.dataCount.get()).isEqualTo(1);
        verify(taskMapper).compareAndSetPhase(1L, MigrationPhase.PRECHECKED, MigrationPhase.SCHEMA_MIGRATION);
        verify(taskMapper).compareAndSetPhase(1L, MigrationPhase.SCHEMA_MIGRATION, MigrationPhase.FULL);
        verify(taskMapper).compareAndSetPhase(1L, MigrationPhase.FULL, MigrationPhase.INCREMENTAL);
        // The row must already say FULL before the data call starts, otherwise a crash mid-snapshot
        // would resume at SCHEMA_MIGRATION and redo the DDL.
        assertThat(engine.phaseAtDataStart).isEqualTo(MigrationPhase.FULL);

        task.setPhase(MigrationPhase.INCREMENTAL);
        task.setState(TaskState.STOPPING);
        job.requestStop();
        thread.join(5_000);

        assertThat(engine.run.stopCalled).isTrue();
        assertThat(engine.run.stoppedTaskId).isEqualTo(1L);
        // 暂停落地：STOPPING → landPausing → STOPPED。
        verify(taskMapper).landPausing(1L);
        assertThat(registry.hasLive(1L)).isFalse();
    }

    @Test
    void dataFailureLeavesPhaseAndMarksFailed() {
        engine.dataFailure = new IllegalStateException("binlog unavailable");
        MigrationTask task = task(2L, MigrationPhase.PRECHECKED, TaskState.STARTING, MigrationMode.FULL_AND_INCREMENTAL);
        stubTask(task);

        job(task).run();

        verify(taskMapper).compareAndSetPhase(2L, MigrationPhase.PRECHECKED, MigrationPhase.SCHEMA_MIGRATION);
        verify(taskMapper).compareAndSetPhase(2L, MigrationPhase.SCHEMA_MIGRATION, MigrationPhase.FULL);
        // Died in the snapshot half: the row stays at FULL, which is what makes the failure legible.
        verify(taskMapper, never()).compareAndSetPhase(2L, MigrationPhase.FULL, MigrationPhase.INCREMENTAL);
        verify(taskMapper).markFailed(eq(2L), eq("Data migration failed: binlog unavailable"));
        assertThat(engine.schemaCount.get()).isEqualTo(1);
        assertThat(engine.dataCount.get()).isEqualTo(1);
    }

    @Test
    void failureMessageCarriesTheCauseChain() {
        engine.dataFailure = new IllegalStateException(
                "Debezium Engine failed for task 2",
                new RuntimeException(new IllegalStateException("Provider X not found")));
        MigrationTask task = task(2L, MigrationPhase.FULL, TaskState.STARTING, MigrationMode.FULL_AND_INCREMENTAL);
        stubTask(task);

        job(task).run();

        // The wrapper alone would leave the operator with "Data migration failed: Debezium Engine
        // failed for task 2" and no hint of the real cause. The message-less link in the middle is
        // skipped rather than rendered as a class name.
        verify(taskMapper).markFailed(
                eq(2L),
                eq("Data migration failed: Debezium Engine failed for task 2"
                        + " <- Provider X not found"));
    }

    @Test
    void schemaFailureStopsBeforeData() {
        engine.schemaFailure = new IllegalStateException("no CREATE privilege");
        MigrationTask task = task(6L, MigrationPhase.PRECHECKED, TaskState.STARTING, MigrationMode.FULL_AND_INCREMENTAL);
        stubTask(task);

        job(task).run();

        assertThat(engine.schemaCount.get()).isEqualTo(1);
        assertThat(engine.dataCount.get()).isZero();
        // The phase is set before the DDL runs, so it moves to SCHEMA_MIGRATION even on failure...
        verify(taskMapper).compareAndSetPhase(6L, MigrationPhase.PRECHECKED, MigrationPhase.SCHEMA_MIGRATION);
        // ...but never past it, so a retry re-enters the (idempotent) structure run.
        verify(taskMapper, never()).compareAndSetPhase(eq(6L), eq(MigrationPhase.SCHEMA_MIGRATION), eq(MigrationPhase.FULL));
        verify(taskMapper).markFailed(eq(6L), eq("Schema migration failed: no CREATE privilege"));
    }

    @Test
    void resumeFromIncrementalSkipsSchema() throws Exception {
        MigrationTask task = task(3L, MigrationPhase.INCREMENTAL, TaskState.RUNNING, MigrationMode.FULL_AND_INCREMENTAL);
        stubTask(task);
        MigrationCoordinator job = job(task);

        Thread thread = new Thread(job, "test-job-3");
        thread.start();
        assertThat(engine.run.started.await(5, TimeUnit.SECONDS)).isTrue();

        assertThat(engine.schemaCount.get()).isZero();
        assertThat(engine.dataCount.get()).isEqualTo(1);
        verify(taskMapper, never()).compareAndSetPhase(eq(3L), eq(MigrationPhase.PRECHECKED), any());
        verify(taskMapper, never()).compareAndSetPhase(eq(3L), eq(MigrationPhase.SCHEMA_MIGRATION), any());

        task.setState(TaskState.STOPPING);
        job.requestStop();
        thread.join(5_000);
        verify(taskMapper).landPausing(3L);
    }

    @Test
    void resumeFromSchemaMigrationRerunsTheStructureRun() throws Exception {
        // SCHEMA_MIGRATION means "the structure step is the current step", not "it finished", so a
        // restart re-enters it. Safe because the migrator skips tables that already exist.
        MigrationTask task = task(7L, MigrationPhase.SCHEMA_MIGRATION, TaskState.RUNNING, MigrationMode.FULL_AND_INCREMENTAL);
        stubTask(task);
        MigrationCoordinator job = job(task);

        Thread thread = new Thread(job, "test-job-7");
        thread.start();
        assertThat(engine.run.started.await(5, TimeUnit.SECONDS)).isTrue();

        assertThat(engine.schemaCount.get()).isEqualTo(1);
        assertThat(engine.dataCount.get()).isEqualTo(1);
        verify(taskMapper).compareAndSetPhase(7L, MigrationPhase.SCHEMA_MIGRATION, MigrationPhase.FULL);
        verify(taskMapper, never()).compareAndSetPhase(eq(7L), eq(MigrationPhase.PRECHECKED), any());

        task.setState(TaskState.STOPPING);
        job.requestStop();
        thread.join(5_000);
    }

    @Test
    void resumeFromFullRunsDataWithPhaseAlreadyCorrect() throws Exception {
        // FULL is a real phase now: the data run is (or was) in its snapshot half. Nothing to
        // unstick, and the structure step must not be repeated.
        MigrationTask task = task(5L, MigrationPhase.FULL, TaskState.RUNNING, MigrationMode.FULL_AND_INCREMENTAL);
        stubTask(task);
        MigrationCoordinator job = job(task);

        Thread thread = new Thread(job, "test-job-5");
        thread.start();
        assertThat(engine.run.started.await(5, TimeUnit.SECONDS)).isTrue();

        assertThat(engine.schemaCount.get()).isZero();
        assertThat(engine.dataCount.get()).isEqualTo(1);
        assertThat(engine.phaseAtDataStart).isEqualTo(MigrationPhase.FULL);
        verify(taskMapper, never()).compareAndSetPhase(eq(5L), eq(MigrationPhase.SCHEMA_MIGRATION), any());

        task.setState(TaskState.STOPPING);
        job.requestStop();
        thread.join(5_000);
    }

    @Test
    void phaseWithoutCaptureIsLeftAlone() {
        MigrationTask task = task(8L, MigrationPhase.CREATED, TaskState.STARTING, MigrationMode.FULL_AND_INCREMENTAL);
        stubTask(task);

        job(task).run();

        assertThat(engine.schemaCount.get()).isZero();
        assertThat(engine.dataCount.get()).isZero();
        verify(taskMapper, never()).markFailed(anyLong(), any());
    }

    private void stubTask(MigrationTask task) {
        when(taskMapper.findById(task.getId())).thenAnswer(invocation -> task);
        when(taskMapper.compareAndSetPhase(eq(task.getId()), eq(MigrationPhase.PRECHECKED), eq(MigrationPhase.SCHEMA_MIGRATION)))
                .thenAnswer(invocation -> {
                    task.setPhase(MigrationPhase.SCHEMA_MIGRATION);
                    return 1;
                });
        when(taskMapper.compareAndSetPhase(eq(task.getId()), eq(MigrationPhase.SCHEMA_MIGRATION), eq(MigrationPhase.FULL)))
                .thenAnswer(invocation -> {
                    task.setPhase(MigrationPhase.FULL);
                    return 1;
                });
        when(taskMapper.compareAndSetPhase(eq(task.getId()), eq(MigrationPhase.FULL), eq(MigrationPhase.INCREMENTAL)))
                .thenAnswer(invocation -> {
                    task.setPhase(MigrationPhase.INCREMENTAL);
                    return 1;
                });
        when(taskMapper.compareAndSetState(eq(task.getId()), eq(TaskState.STARTING), eq(TaskState.RUNNING)))
                .thenAnswer(invocation -> {
                    task.setState(TaskState.RUNNING);
                    return 1;
                });
        when(taskMapper.compareAndSetState(eq(task.getId()), eq(TaskState.STOPPING), eq(TaskState.STOPPED)))
                .thenAnswer(invocation -> {
                    task.setState(TaskState.STOPPED);
                    return 1;
                });
        when(taskMapper.compareAndSetState(eq(task.getId()), eq(TaskState.RUNNING), eq(TaskState.STOPPED)))
                .thenAnswer(invocation -> {
                    task.setState(TaskState.STOPPED);
                    return 1;
                });
    }

    private MigrationCoordinator job(MigrationTask task) {
        engine.phaseSupplier = task::getPhase;
        MigrationCoordinator job = new MigrationCoordinator(
                task.getId(),
                task.getMode(),
                taskMapper,
                connectionMapper,
                registry,
                engine);
        registry.tryRegister(job);
        return job;
    }

    private static MigrationTask task(long id, MigrationPhase phase, TaskState state, MigrationMode mode) {
        MigrationTask task = new MigrationTask();
        task.setPublicId(UUID.randomUUID().toString());
        task.setId(id);
        task.setMode(mode);
        task.setPhase(phase);
        task.setState(state);
        task.setSourceConnectionId(10L);
        task.setTargetConnectionId(11L);
        task.setOptionsJson(new MigrationOptions(
                MigrationOptions.Topology.ONE_WAY,
                MigrationOptions.Selection.ofTables(List.of(new SelectedTable("hr", "emp"))),
                MigrationOptions.FullSettings.defaults(),
                MigrationOptions.IncrementalSettings.defaults(),
                null));
        return task;
    }

    private static ConnectionInfo mysql() {
        ConnectionInfo info = new ConnectionInfo();
        info.setType(DbType.MYSQL);
        info.setHost("localhost");
        info.setPort(3306);
        info.setDbName("hr");
        info.setUsername("u");
        info.setPassword("p");
        return info;
    }

    /** Stands in for the engine: one immediate schema handle and one blocking data handle. */
    static final class FakeMigrationEngine implements MigrationEngine {

        final FakeRun run = new FakeRun();
        final AtomicInteger schemaCount = new AtomicInteger();
        final AtomicInteger dataCount = new AtomicInteger();
        RuntimeException schemaFailure;
        RuntimeException dataFailure;
        /** Reads the persisted phase, so a test can assert what the row said when data started. */
        Supplier<MigrationPhase> phaseSupplier = () -> null;
        volatile MigrationPhase phaseAtDataStart;

        @Override
        public boolean supportsPrecheck(DatabaseType source, DatabaseType target) {
            return false;
        }

        @Override
        public SchemaMigrationTask migrateSchema(SchemaMigrationParam param) {
            schemaCount.incrementAndGet();
            return new FakeSchemaTask(schemaFailure);
        }

        @Override
        public DataMigrationTask migrateData(DataMigrationParam param) {
            dataCount.incrementAndGet();
            phaseAtDataStart = phaseSupplier.get();
            if (dataFailure != null) {
                return new FakeDataTask(param.taskId(), dataFailure, run);
            }
            if (param.phaseListener() != null) {
                param.phaseListener().accept(DataMigrationTaskPhase.FULL);
                param.phaseListener().accept(DataMigrationTaskPhase.INCREMENTAL);
            }
            return new FakeDataTask(param.taskId(), null, run);
        }

        @Override
        public PrecheckTask precheck(PrecheckParam param) {
            throw new UnsupportedOperationException("precheck is not exercised by MigrationCoordinatorTest");
        }
    }

    /** Already finished when handed out: the schema run is short. */
    static final class FakeSchemaTask implements SchemaMigrationTask {

        private final Throwable failure;

        FakeSchemaTask(Throwable failure) {
            this.failure = failure;
        }

        @Override
        public List<Table> created() {
            return List.of();
        }

        @Override
        public List<Table> skipped() {
            return List.of();
        }

        @Override
        public TaskState state() {
            return failure == null ? TaskState.COMPLETED : TaskState.FAILED;
        }

        @Override
        public Throwable failure() {
            return failure;
        }

        @Override
        public void requestStop() {
            // nothing to stop: the fake finishes synchronously
        }

        @Override
        public void await() {
            // already done
        }
    }

    /**
     * Blocks in {@code await()} until stopped. {@code started} is released from {@code await()},
     * not from {@code migrateData}: only then has the coordinator published the handle, so a test
     * that calls {@code requestStop()} afterwards is guaranteed to reach this instance.
     */
    static final class FakeDataTask implements DataMigrationTask {

        private final long taskId;
        private final Throwable failure;
        private final FakeRun run;
        private final AtomicReference<TaskState> state = new AtomicReference<>(TaskState.RUNNING);

        FakeDataTask(long taskId, Throwable failure, FakeRun run) {
            this.taskId = taskId;
            this.failure = failure;
            this.run = run;
            if (failure != null) {
                state.set(TaskState.FAILED);
            }
        }

        @Override
        public DataMigrationTaskPhase taskPhase() {
            // A run that failed never got past the snapshot.
            return failure == null ? DataMigrationTaskPhase.INCREMENTAL : DataMigrationTaskPhase.FULL;
        }

        @Override
        public TaskState state() {
            return state.get();
        }

        @Override
        public Throwable failure() {
            return failure;
        }

        @Override
        public void requestStop() {
            run.stopCalled = true;
            run.stoppedTaskId = taskId;
            run.finishStop();
        }

        @Override
        public void await() throws InterruptedException {
            run.started.countDown();
            if (failure != null) {
                return;
            }
            run.stop.await(10, TimeUnit.SECONDS);
            state.set(TaskState.STOPPED);
        }
    }

    static final class FakeRun {

        final CountDownLatch started = new CountDownLatch(1);
        final CountDownLatch stop = new CountDownLatch(1);
        volatile boolean stopCalled;
        volatile long stoppedTaskId = -1L;

        void finishStop() {
            stop.countDown();
        }
    }
}
