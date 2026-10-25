package io.opendrs.migration.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

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
import io.opendrs.jdbc.metadata.Table;
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
 * The coordinator's resume branch end to end (engine faked): done tables are skipped, the
 * remaining set reaches the engine param, the half-done tables are truncated on the target.
 * The real {@link FullLoadResumePlanner} decides; the checkpoint mapper and both JDBC sides
 * are mocked.
 */
class MigrationCoordinatorResumeTest {

    private MigrationTaskMapper taskMapper;
    private ConnectionInfoMapper connectionMapper;
    private FullLoadCheckpointMapper checkpointMapper;
    private JdbcConnectionFactory connectionFactory;
    private FullLoadResumePlanner resumePlanner;
    private MigrationCoordinatorRegistry registry;
    private CapturingEngine engine;

    private JdbcConnection targetConnection;
    private List<String> executedSql;

    @BeforeEach
    void setUp() throws Exception {
        taskMapper = Mockito.mock(MigrationTaskMapper.class);
        connectionMapper = Mockito.mock(ConnectionInfoMapper.class);
        checkpointMapper = Mockito.mock(FullLoadCheckpointMapper.class);
        connectionFactory = Mockito.mock(JdbcConnectionFactory.class);
        resumePlanner = new FullLoadResumePlanner(checkpointMapper, new MappingValidator(), connectionFactory);
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
    }

    @Test
    void doneTablesAreSkippedAndRemainingTablesReachTheEngine() throws Exception {
        MigrationTask task = task(21L, MigrationPhase.FULL, TaskState.STARTING, MigrationMode.FULL_AND_INCREMENTAL);
        stubTask(task);
        when(checkpointMapper.findDoneTables(21L, "forward")).thenReturn(Set.of("hr.emp"));
        when(checkpointMapper.findAllTables(21L, "forward")).thenReturn(List.of("hr.dept", "hr.emp"));

        MigrationCoordinator job = job(task);
        Thread thread = new Thread(job, "resume-job-21");
        thread.start();
        assertThat(engine.run.started.await(5, TimeUnit.SECONDS)).isTrue();

        DataMigrationParam param = engine.capturedParam.get();
        assertThat(param).isNotNull();
        assertThat(param.resumeTables()).containsExactly("hr.dept");
        assertThat(executedSql).containsExactly("truncate table `hr`.`dept`");

        task.setState(TaskState.STOPPING);
        job.requestStop();
        thread.join(5_000);
    }

    @Test
    void noCheckpointsKeepsTheNormalFullSnapshot() throws Exception {
        MigrationTask task = task(22L, MigrationPhase.FULL, TaskState.STARTING, MigrationMode.FULL_AND_INCREMENTAL);
        stubTask(task);
        when(checkpointMapper.findDoneTables(22L, "forward")).thenReturn(Set.of());

        MigrationCoordinator job = job(task);
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
    void fullOnlyModeNeverResumes() {
        // EngineSpec rejects resumeTables with anything but the INITIAL strategy, and re-running
        // a finished FULL_ONLY snapshot is the existing dup-key → upsert digest — 保持不变。
        MigrationTask task = task(23L, MigrationPhase.FULL, TaskState.STARTING, MigrationMode.FULL_ONLY);
        stubTask(task);
        engine.finiteData = true;
        when(checkpointMapper.findDoneTables(23L, "forward")).thenReturn(Set.of("hr.emp"));
        when(checkpointMapper.findAllTables(23L, "forward")).thenReturn(List.of("hr.dept", "hr.emp"));

        job(task).run();

        assertThat(engine.capturedParam.get().resumeTables()).isNull();
        assertThat(executedSql).isEmpty();
        verify(checkpointMapper, never()).findDoneTables(anyLong(), anyString());
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
        when(checkpointMapper.findDoneTables(25L, "forward"))
                .thenReturn(new LinkedHashSet<>(List.of("hr.dept", "hr.emp")));
        when(checkpointMapper.findAllTables(25L, "forward")).thenReturn(List.of("hr.dept", "hr.emp"));

        MigrationCoordinator job = job(task);
        Thread thread = new Thread(job, "resume-job-25");
        thread.start();
        assertThat(engine.run.started.await(5, TimeUnit.SECONDS)).isTrue();

        assertThat(engine.capturedParam.get().resumeTables()).isNull();
        assertThat(executedSql).isEmpty();

        task.setState(TaskState.STOPPING);
        job.requestStop();
        thread.join(5_000);
    }

    private MigrationCoordinator job(MigrationTask task) {
        return new MigrationCoordinator(
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
                null,
                resumePlanner);
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
            if (finiteData) {
                if (param.phaseListener() != null) {
                    param.phaseListener().accept(DataMigrationTaskPhase.FULL);
                    param.phaseListener().accept(DataMigrationTaskPhase.INCREMENTAL);
                }
                return new FiniteDataTask();
            }
            if (param.phaseListener() != null) {
                param.phaseListener().accept(DataMigrationTaskPhase.FULL);
                param.phaseListener().accept(DataMigrationTaskPhase.INCREMENTAL);
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
            return TaskState.COMPLETED == null ? null : DataMigrationTaskPhase.FULL;
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
