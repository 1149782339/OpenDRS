package io.opendrs.migration.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.opendrs.debezium.CdcEngine;
import io.opendrs.debezium.CdcEngineFactory;
import io.opendrs.debezium.DebeziumEngineConfig.EngineSpec;
import io.opendrs.jdbc.metadata.Table;
import io.opendrs.jdbc.metadata.TableRef;
import io.opendrs.migration.api.request.MigrationOptions;
import io.opendrs.migration.api.request.SchemaObject;
import io.opendrs.migration.api.request.TableSelection;
import io.opendrs.migration.domain.ConnectionInfo;
import io.opendrs.migration.domain.DbType;
import io.opendrs.migration.domain.JobPhase;
import io.opendrs.migration.domain.JobState;
import io.opendrs.migration.domain.MigrationMode;
import io.opendrs.migration.domain.MigrationTask;
import io.opendrs.migration.mapper.ConnectionInfoMapper;
import io.opendrs.migration.mapper.MigrationTaskMapper;
import io.opendrs.migration.service.TableSelectionExpander;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class TaskJobTest {

    private MigrationTaskMapper taskMapper;
    private ConnectionInfoMapper connectionMapper;
    private TaskJobRegistry registry;
    private TableSelectionExpander expander;
    private FakeEngineFactory engines;

    @BeforeEach
    void setUp() {
        taskMapper = Mockito.mock(MigrationTaskMapper.class);
        connectionMapper = Mockito.mock(ConnectionInfoMapper.class);
        registry = new TaskJobRegistry();
        expander = Mockito.mock(TableSelectionExpander.class);
        engines = new FakeEngineFactory();
        when(expander.expand(any(), any())).thenReturn(List.of(new Table(new TableRef("hr", "emp"))));
        when(connectionMapper.findById(anyLong())).thenReturn(mysql());
        when(taskMapper.compareAndSetJobState(anyLong(), any(), any())).thenReturn(1);
        when(taskMapper.compareAndSetPhase(anyLong(), any(), any())).thenReturn(1);
        when(taskMapper.markJobFailed(anyLong(), any())).thenReturn(1);
    }

    @Test
    void oneEngineSnapshotsThenStreamsUntilStop() throws Exception {
        MigrationTask task = task(1L, JobPhase.PRECHECKED, JobState.STARTING, MigrationMode.FULL_AND_INCREMENTAL);
        stubTask(task);
        TaskJob job = job(task);
        registry.tryRegister(job);

        Thread thread = new Thread(job, "test-job-1");
        thread.start();
        assertThat(engines.engine.runStarted.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(engines.createCount.get()).isEqualTo(1);
        verify(taskMapper).compareAndSetPhase(1L, JobPhase.PRECHECKED, JobPhase.SCHEMA_SNAPSHOT);
        verify(taskMapper).compareAndSetPhase(1L, JobPhase.SCHEMA_SNAPSHOT, JobPhase.INCREMENTAL);
        verify(taskMapper, never()).compareAndSetPhase(1L, JobPhase.SCHEMA_SNAPSHOT, JobPhase.FULL);
        verify(taskMapper, never()).compareAndSetPhase(eq(1L), eq(JobPhase.FULL), eq(JobPhase.INCREMENTAL));

        task.setJobPhase(JobPhase.INCREMENTAL);
        task.setJobState(JobState.STOPPING);
        job.requestStop();
        thread.join(5_000);

        assertThat(engines.engine.stopCalled).isTrue();
        verify(taskMapper).compareAndSetJobState(1L, JobState.STOPPING, JobState.STOPPED);
        assertThat(registry.hasLive(1L)).isFalse();
    }

    @Test
    void engineFailureLeavesPhaseAndMarksFailed() throws Exception {
        engines.failure = new IllegalStateException("binlog unavailable");
        MigrationTask task = task(2L, JobPhase.PRECHECKED, JobState.STARTING, MigrationMode.FULL_AND_INCREMENTAL);
        stubTask(task);
        TaskJob job = job(task);

        job.run();

        verify(taskMapper).compareAndSetPhase(2L, JobPhase.PRECHECKED, JobPhase.SCHEMA_SNAPSHOT);
        verify(taskMapper, never()).compareAndSetPhase(2L, JobPhase.SCHEMA_SNAPSHOT, JobPhase.INCREMENTAL);
        verify(taskMapper, never()).compareAndSetPhase(2L, JobPhase.SCHEMA_SNAPSHOT, JobPhase.FULL);
        verify(taskMapper).markJobFailed(eq(2L), eq("binlog unavailable"));
        assertThat(engines.createCount.get()).isEqualTo(1);
    }

    @Test
    void resumeFromIncrementalStartsOneEngine() throws Exception {
        MigrationTask task = task(3L, JobPhase.INCREMENTAL, JobState.RUNNING, MigrationMode.FULL_AND_INCREMENTAL);
        stubTask(task);
        TaskJob job = job(task);

        Thread thread = new Thread(job, "test-job-3");
        thread.start();
        assertThat(engines.engine.runStarted.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(engines.createCount.get()).isEqualTo(1);
        task.setJobState(JobState.STOPPING);
        job.requestStop();
        thread.join(5_000);

        verify(taskMapper, never()).compareAndSetPhase(eq(3L), eq(JobPhase.PRECHECKED), any());
        verify(taskMapper, never()).compareAndSetPhase(eq(3L), eq(JobPhase.SCHEMA_SNAPSHOT), any());
    }

    @Test
    void fullOnlyRunsSingleEngineUntilStop() throws Exception {
        MigrationTask task = task(4L, JobPhase.PRECHECKED, JobState.STARTING, MigrationMode.FULL_ONLY);
        stubTask(task);
        TaskJob job = job(task);
        registry.tryRegister(job);

        Thread thread = new Thread(job, "test-job-4");
        thread.start();
        assertThat(engines.engine.runStarted.await(5, TimeUnit.SECONDS)).isTrue();
        verify(taskMapper).compareAndSetPhase(4L, JobPhase.PRECHECKED, JobPhase.SCHEMA_SNAPSHOT);
        verify(taskMapper).compareAndSetPhase(4L, JobPhase.SCHEMA_SNAPSHOT, JobPhase.INCREMENTAL);
        verify(taskMapper, never()).compareAndSetPhase(eq(4L), eq(JobPhase.SCHEMA_SNAPSHOT), eq(JobPhase.FULL));
        assertThat(engines.createCount.get()).isEqualTo(1);

        task.setJobState(JobState.STOPPING);
        job.requestStop();
        thread.join(5_000);
        verify(taskMapper).compareAndSetJobState(4L, JobState.STOPPING, JobState.STOPPED);
    }

    @Test
    void resumeFromFullUnsticksPhaseAndStartsEngine() throws Exception {
        MigrationTask task = task(5L, JobPhase.FULL, JobState.RUNNING, MigrationMode.FULL_AND_INCREMENTAL);
        stubTask(task);
        TaskJob job = job(task);

        Thread thread = new Thread(job, "test-job-5");
        thread.start();
        assertThat(engines.engine.runStarted.await(5, TimeUnit.SECONDS)).isTrue();
        verify(taskMapper).compareAndSetPhase(5L, JobPhase.FULL, JobPhase.INCREMENTAL);
        verify(taskMapper, never()).compareAndSetPhase(eq(5L), eq(JobPhase.PRECHECKED), any());
        assertThat(engines.createCount.get()).isEqualTo(1);

        task.setJobState(JobState.STOPPING);
        job.requestStop();
        thread.join(5_000);
    }

    private void stubTask(MigrationTask task) {
        when(taskMapper.findById(task.getId())).thenAnswer(invocation -> task);
        when(taskMapper.compareAndSetPhase(eq(task.getId()), eq(JobPhase.PRECHECKED), eq(JobPhase.SCHEMA_SNAPSHOT)))
                .thenAnswer(invocation -> {
                    task.setJobPhase(JobPhase.SCHEMA_SNAPSHOT);
                    return 1;
                });
        when(taskMapper.compareAndSetPhase(eq(task.getId()), eq(JobPhase.SCHEMA_SNAPSHOT), eq(JobPhase.INCREMENTAL)))
                .thenAnswer(invocation -> {
                    task.setJobPhase(JobPhase.INCREMENTAL);
                    return 1;
                });
        when(taskMapper.compareAndSetPhase(eq(task.getId()), eq(JobPhase.FULL), eq(JobPhase.INCREMENTAL)))
                .thenAnswer(invocation -> {
                    task.setJobPhase(JobPhase.INCREMENTAL);
                    return 1;
                });
        when(taskMapper.compareAndSetJobState(eq(task.getId()), eq(JobState.STARTING), eq(JobState.RUNNING)))
                .thenAnswer(invocation -> {
                    task.setJobState(JobState.RUNNING);
                    return 1;
                });
        when(taskMapper.compareAndSetJobState(eq(task.getId()), eq(JobState.STOPPING), eq(JobState.STOPPED)))
                .thenAnswer(invocation -> {
                    task.setJobState(JobState.STOPPED);
                    return 1;
                });
        when(taskMapper.compareAndSetJobState(eq(task.getId()), eq(JobState.RUNNING), eq(JobState.STOPPED)))
                .thenAnswer(invocation -> {
                    task.setJobState(JobState.STOPPED);
                    return 1;
                });
    }

    private TaskJob job(MigrationTask task) {
        return new TaskJob(
                task.getId(),
                task.getMode(),
                taskMapper,
                connectionMapper,
                registry,
                expander,
                engines);
    }

    private static MigrationTask task(long id, JobPhase phase, JobState state, MigrationMode mode) {
        MigrationTask task = new MigrationTask();
        task.setId(id);
        task.setMode(mode);
        task.setJobPhase(phase);
        task.setJobState(state);
        task.setSourceConnectionId(10L);
        task.setOptionsJson(new MigrationOptions(8, 1000, 85744L));
        task.setTablesJson(new TableSelection(List.of(new SchemaObject("hr", List.of("emp"), null, null)), null));
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

    static final class FakeEngineFactory implements CdcEngineFactory {
        final FakeEngine engine = new FakeEngine();
        final AtomicInteger createCount = new AtomicInteger();
        RuntimeException failure;

        @Override
        public CdcEngine create(EngineSpec spec, Runnable onSnapshotCompleted) {
            createCount.incrementAndGet();
            engine.failure = failure;
            engine.onSnapshotCompleted = onSnapshotCompleted;
            return engine;
        }
    }

    static final class FakeEngine implements CdcEngine {
        final CountDownLatch runStarted = new CountDownLatch(1);
        final CountDownLatch finish = new CountDownLatch(1);
        volatile boolean stopCalled;
        RuntimeException failure;
        Runnable onSnapshotCompleted;

        @Override
        public void run() {
            if (failure != null) {
                runStarted.countDown();
                throw failure;
            }
            if (onSnapshotCompleted != null) {
                onSnapshotCompleted.run();
            }
            runStarted.countDown();
            try {
                if (!finish.await(10, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("fake engine timed out");
                }
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
        }

        @Override
        public void stop() {
            stopCalled = true;
            finish.countDown();
        }
    }
}
