package io.opendrs.migration.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.opendrs.common.log.MigrationLogListener;
import io.opendrs.engine.core.DataMigrationParam;
import io.opendrs.engine.core.DataMigrationTask;
import io.opendrs.engine.core.DataMigrationTaskPhase;
import io.opendrs.engine.core.MigrationEngine;
import io.opendrs.engine.core.PrecheckParam;
import io.opendrs.engine.core.PrecheckTask;
import io.opendrs.engine.core.SchemaMigrationParam;
import io.opendrs.engine.core.SchemaMigrationTask;
import io.opendrs.engine.core.TaskState;
import io.opendrs.engine.replay.mapping.MappingValidator;
import io.opendrs.jdbc.JdbcConnection;
import io.opendrs.jdbc.JdbcConnectionFactory;
import io.opendrs.migration.api.request.MigrationOptions;
import io.opendrs.migration.api.request.SelectedTable;
import io.opendrs.migration.domain.ConnectionInfo;
import io.opendrs.migration.domain.DbType;
import io.opendrs.migration.domain.MigrationMode;
import io.opendrs.migration.domain.MigrationPhase;
import io.opendrs.migration.domain.MigrationTask;
import io.opendrs.migration.mapper.ConnectionInfoMapper;
import io.opendrs.migration.mapper.FullLoadCheckpointMapper;
import io.opendrs.migration.mapper.MigrationTaskMapper;
import io.opendrs.migration.service.FullLoadResumePlanner;
import io.opendrs.migration.service.MigrationLogService;
import java.sql.DatabaseMetaData;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * The coordinator's resume branch end to end (engine faked): completed tables are skipped, the
 * remaining set reaches the engine param, the half-done tables are truncated on the target. The
 * real {@link FullLoadResumePlanner} decides; the checkpoint mapper and both JDBC sides are
 * mocked. Unit tests get one ephemeral pipeline whose id equals the task id.
 */
class MigrationCoordinatorResumeTest {

    private MigrationTaskMapper taskMapper;
    private ConnectionInfoMapper connectionMapper;
    private FullLoadCheckpointMapper checkpointMapper;
    private JdbcConnectionFactory connectionFactory;
    private MigrationLogService logService;
    private FullLoadResumePlanner resumePlanner;
    private MigrationCoordinatorRegistry registry;
    private CapturingEngine engine;

    private JdbcConnection targetConnection;
    private List<String> executedSql;
    private List<String> notes;

    @BeforeEach
    void setUp() throws Exception {
        taskMapper = Mockito.mock(MigrationTaskMapper.class);
        connectionMapper = Mockito.mock(ConnectionInfoMapper.class);
        checkpointMapper = Mockito.mock(FullLoadCheckpointMapper.class);
        connectionFactory = Mockito.mock(JdbcConnectionFactory.class);
        logService = Mockito.mock(MigrationLogService.class);
        when(logService.listenerFor(anyLong())).thenReturn(MigrationLogListener.NONE);
        resumePlanner = new FullLoadResumePlanner(checkpointMapper, new MappingValidator(),
                connectionFactory, logService);
        registry = new MigrationCoordinatorRegistry();
        engine = new CapturingEngine();
        when(connectionMapper.findById(anyLong())).thenReturn(mysql());
        when(taskMapper.compareAndSetState(anyLong(), any(), any())).thenReturn(1);
        when(taskMapper.compareAndSetPhase(anyLong(), any(), any())).thenReturn(1);
        when(taskMapper.markFailed(anyLong(), any())).thenReturn(1);

        targetConnection = mock(JdbcConnection.class);
        executedSql = new ArrayList<>();
        doAnswer(invocation -> {
            executedSql.add(invocation.getArgument(0));
            return null;
        }).when(targetConnection).execute(anyString());
        java.sql.Connection raw = mock(java.sql.Connection.class);
        DatabaseMetaData metaData = mock(DatabaseMetaData.class);
        when(raw.getMetaData()).thenReturn(metaData);
        when(metaData.getIdentifierQuoteString()).thenReturn("`");
        when(targetConnection.unwrap()).thenReturn(raw);
        when(connectionFactory.open(any())).thenReturn(targetConnection);
        notes = new ArrayList<>();
    }

    @Test
    void doneTablesAreSkippedAndRemainingTablesReachTheEngine() throws Exception {
        MigrationTask task = task(21L, MigrationPhase.FULL, TaskState.STARTING, MigrationMode.FULL_AND_INCREMENTAL);
        stubTask(task);
        // The ephemeral pipeline's id equals the task id.
        when(checkpointMapper.findDoneTables(21L)).thenReturn(Set.of("hr.emp"));

        MigrationCoordinator job = job(task, notingListener());
        Thread thread = new Thread(job, "resume-job-21");
        thread.start();
        assertThat(engine.run.started.await(5, TimeUnit.SECONDS)).isTrue();

        DataMigrationParam param = engine.capturedParam.get();
        assertThat(param).isNotNull();
        assertThat(param.resumeTables()).containsExactly("hr.dept");
        assertThat(executedSql).containsExactly("truncate table `hr`.`dept`");
        assertThat(notes).anyMatch(m -> m.contains(
                "resume full load: 1 table(s) to re-snapshot, done table(s) skipped"));

        task.setState(TaskState.STOPPING);
        job.requestStop();
        thread.join(5_000);
    }

    @Test
    void noCompletedTablesKeepsTheNormalFullSnapshot() throws Exception {
        MigrationTask task = task(22L, MigrationPhase.FULL, TaskState.STARTING, MigrationMode.FULL_AND_INCREMENTAL);
        stubTask(task);
        when(checkpointMapper.findDoneTables(22L)).thenReturn(Set.of());

        MigrationCoordinator job = job(task, MigrationLogListener.NONE);
        Thread thread = new Thread(job, "resume-job-22");
        thread.start();
        assertThat(engine.run.started.await(5, TimeUnit.SECONDS)).isTrue();

        assertThat(engine.capturedParam.get().resumeTables()).isNull();
        assertThat(executedSql).isEmpty();

        task.setState(TaskState.STOPPING);
        job.requestStop();
        thread.join(5_000);
    }

    @Test
    void fullOnlyModeResumesTooAndLeavesTheOfflineAssumptionWarning() {
        // FULL_ONLY implies a quiesced source, so table-level resume is safe: done tables are
        // skipped, the rest re-snapshotted — the assumption is narrated as a warning.
        MigrationTask task = task(23L, MigrationPhase.FULL, TaskState.STARTING, MigrationMode.FULL_ONLY);
        stubTask(task);
        engine.finiteData = true;
        when(checkpointMapper.findDoneTables(23L)).thenReturn(Set.of("hr.emp"));

        job(task, notingListener()).run();

        assertThat(engine.capturedParam.get().resumeTables()).containsExactly("hr.dept");
        assertThat(executedSql).containsExactly("truncate table `hr`.`dept`");
        assertThat(notes).anyMatch(m -> m.contains("FULL_ONLY resume assumes the source is unchanged"));
    }

    @Test
    void coordinatorWithoutPlannerNeverResumes() throws Exception {
        MigrationTask task = task(24L, MigrationPhase.FULL, TaskState.STARTING, MigrationMode.FULL_AND_INCREMENTAL);
        stubTask(task);
        MigrationCoordinator job = new MigrationCoordinator(
                task.getId(),
                task.getMode(),
                taskMapper,
                connectionMapper,
                registry,
                engine,
                io.opendrs.common.log.MigrationLogListener.NONE,
                null,
                null,
                null,
                null);
        registry.tryRegister(job);
        Thread thread = new Thread(job, "resume-job-24");
        thread.start();
        assertThat(engine.run.started.await(5, TimeUnit.SECONDS)).isTrue();

        assertThat(engine.capturedParam.get().resumeTables()).isNull();
        verifyNoInteractions(checkpointMapper);

        task.setState(TaskState.STOPPING);
        job.requestStop();
        thread.join(5_000);
    }

    @Test
    void allDoneResumesStraightIntoIncremental() throws Exception {
        // 全 done：不传 resumeTables，引擎按 INITIAL 正常启动，存量位点使其跳过快照进增量。
        MigrationTask task = task(25L, MigrationPhase.FULL, TaskState.STARTING, MigrationMode.FULL_AND_INCREMENTAL);
        stubTask(task);
        when(checkpointMapper.findDoneTables(25L))
                .thenReturn(new LinkedHashSet<>(List.of("hr.dept", "hr.emp")));

        MigrationCoordinator job = job(task, MigrationLogListener.NONE);
        Thread thread = new Thread(job, "resume-job-25");
        thread.start();
        assertThat(engine.run.started.await(5, TimeUnit.SECONDS)).isTrue();

        assertThat(engine.capturedParam.get().resumeTables()).isNull();
        assertThat(executedSql).isEmpty();

        task.setState(TaskState.STOPPING);
        job.requestStop();
        thread.join(5_000);
    }

    private MigrationCoordinator job(MigrationTask task, io.opendrs.common.log.MigrationLogListener logListener) {
        return new MigrationCoordinator(
                task.getId(),
                task.getMode(),
                taskMapper,
                connectionMapper,
                registry,
                engine,
                logListener,
                null,
                null,
                null,
                null,
                resumePlanner);
    }

    private io.opendrs.common.log.MigrationLogListener notingListener() {
        return (level, message) -> notes.add(level + ":" + message);
    }

    private void stubTask(MigrationTask task) {
        when(taskMapper.findById(task.getId())).thenAnswer(invocation -> task);
        when(taskMapper.compareAndSetState(eq(task.getId()), eq(TaskState.STARTING), eq(TaskState.RUNNING)))
                .thenAnswer(invocation -> {
                    task.setState(TaskState.RUNNING);
                    return 1;
                });
        when(taskMapper.compareAndSetState(eq(task.getId()), eq(TaskState.RUNNING), eq(TaskState.STOPPED)))
                .thenAnswer(invocation -> {
                    task.setState(TaskState.STOPPED);
                    return 1;
                });
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
                MigrationOptions.Selection.ofTables(List.of(
                        new SelectedTable("hr", "emp"), new SelectedTable("hr", "dept"))),
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

    /** Records the data param and hands out one blocking data handle. */
    static final class CapturingEngine implements MigrationEngine {

        final FakeRun run = new FakeRun();
        final AtomicReference<DataMigrationParam> capturedParam = new AtomicReference<>();
        volatile boolean finiteData;

        @Override
        public boolean supportsPrecheck(io.opendrs.common.metadata.DatabaseType source,
                io.opendrs.common.metadata.DatabaseType target) {
            return false;
        }

        @Override
        public SchemaMigrationTask migrateSchema(SchemaMigrationParam param) {
            throw new UnsupportedOperationException("schema migration is not exercised here");
        }

        @Override
        public DataMigrationTask migrateData(DataMigrationParam param) {
            capturedParam.set(param);
            if (param.phaseListener() != null) {
                param.phaseListener().accept(DataMigrationTaskPhase.FULL);
                param.phaseListener().accept(DataMigrationTaskPhase.INCREMENTAL);
            }
            if (finiteData) {
                return new FiniteDataTask();
            }
            return new BlockingDataTask(param.taskId(), run);
        }

        @Override
        public PrecheckTask precheck(PrecheckParam param) {
            throw new UnsupportedOperationException("precheck is not exercised here");
        }
    }

    static final class BlockingDataTask implements DataMigrationTask {

        private final long taskId;
        private final FakeRun run;

        BlockingDataTask(long taskId, FakeRun run) {
            this.taskId = taskId;
            this.run = run;
        }

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
            run.stopCalled = true;
            run.stoppedTaskId = taskId;
            run.finishStop();
        }

        @Override
        public void await() throws InterruptedException {
            run.started.countDown();
            run.stop.await(10, TimeUnit.SECONDS);
        }
    }

    static final class FiniteDataTask implements DataMigrationTask {

        @Override
        public DataMigrationTaskPhase taskPhase() {
            return DataMigrationTaskPhase.FULL;
        }

        @Override
        public TaskState state() {
            return TaskState.COMPLETED;
        }

        @Override
        public Throwable failure() {
            return null;
        }

        @Override
        public void requestStop() {
            // already finished
        }

        @Override
        public void await() {
            // already finished
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
