package io.opendrs.migration.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
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
import io.opendrs.migration.mapper.DebeziumOffsetMapper;
import io.opendrs.migration.mapper.MigrationTaskMapper;
import io.opendrs.migration.service.TableSelectionExpander;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class TaskJobTest {

    private MigrationTaskMapper taskMapper;
    private ConnectionInfoMapper connectionMapper;
    private DebeziumOffsetMapper offsetMapper;
    private TaskJobRegistry registry;
    private TableSelectionExpander expander;
    private FakeEngineFactory engines;

    @BeforeEach
    void setUp() {
        taskMapper = Mockito.mock(MigrationTaskMapper.class);
        connectionMapper = Mockito.mock(ConnectionInfoMapper.class);
        offsetMapper = Mockito.mock(DebeziumOffsetMapper.class);
        registry = new TaskJobRegistry();
        expander = Mockito.mock(TableSelectionExpander.class);
        engines = new FakeEngineFactory();
        when(offsetMapper.findByTaskId(anyLong())).thenReturn(List.of());
        when(expander.expand(any(), any())).thenReturn(List.of(new Table(new TableRef("hr", "emp"))));
        when(connectionMapper.findById(anyLong())).thenReturn(mysql());
        when(taskMapper.compareAndSetJobState(anyLong(), any(), any())).thenReturn(1);
        when(taskMapper.compareAndSetPhase(anyLong(), any(), any())).thenReturn(1);
        when(taskMapper.markJobFailed(anyLong(), any())).thenReturn(1);
    }

    @Test
    void schemaThenFullThenIncrementalUntilStop() throws Exception {
        MigrationTask task = task(1L, JobPhase.PRECHECKED, JobState.STARTING, MigrationMode.FULL_AND_INCREMENTAL);
        stubTask(task);
        TaskJob job = job(task);
        registry.tryRegister(job);

        Thread thread = new Thread(job, "test-job-1");
        thread.start();
        assertThat(engines.schema.runStarted.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(engines.incremental.runStarted.await(5, TimeUnit.SECONDS)).isTrue();
        verify(taskMapper).compareAndSetPhase(1L, JobPhase.PRECHECKED, JobPhase.SCHEMA_SNAPSHOT);
        verify(taskMapper).compareAndSetPhase(1L, JobPhase.SCHEMA_SNAPSHOT, JobPhase.FULL);
        verify(taskMapper).compareAndSetPhase(1L, JobPhase.FULL, JobPhase.INCREMENTAL);
        verify(offsetMapper, atLeastOnce()).findByTaskId(1L);

        task.setJobPhase(JobPhase.INCREMENTAL);
        task.setJobState(JobState.STOPPING);
        job.requestStop();
        thread.join(5_000);

        assertThat(engines.incremental.stopCalled).isTrue();
        verify(taskMapper).compareAndSetJobState(1L, JobState.STOPPING, JobState.STOPPED);
        assertThat(registry.hasLive(1L)).isFalse();
    }

    @Test
    void schemaFailureLeavesPhaseAndMarksFailed() throws Exception {
        engines.schemaFailure = new IllegalStateException("binlog unavailable");
        MigrationTask task = task(2L, JobPhase.PRECHECKED, JobState.STARTING, MigrationMode.FULL_AND_INCREMENTAL);
        stubTask(task);
        TaskJob job = job(task);

        job.run();

        verify(taskMapper).compareAndSetPhase(2L, JobPhase.PRECHECKED, JobPhase.SCHEMA_SNAPSHOT);
        verify(taskMapper, never()).compareAndSetPhase(2L, JobPhase.SCHEMA_SNAPSHOT, JobPhase.FULL);
        verify(taskMapper).markJobFailed(eq(2L), eq("binlog unavailable"));
        verify(taskMapper, never()).compareAndSetPhase(eq(2L), eq(JobPhase.FULL), eq(JobPhase.INCREMENTAL));
    }

    @Test
    void resumeFromIncrementalSkipsSchemaEngine() throws Exception {
        MigrationTask task = task(3L, JobPhase.INCREMENTAL, JobState.RUNNING, MigrationMode.FULL_AND_INCREMENTAL);
        stubTask(task);
        TaskJob job = job(task);

        Thread thread = new Thread(job, "test-job-3");
        thread.start();
        assertThat(engines.incremental.runStarted.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(engines.schemaCreated).isFalse();
        task.setJobState(JobState.STOPPING);
        job.requestStop();
        thread.join(5_000);

        verify(taskMapper, never()).compareAndSetPhase(eq(3L), eq(JobPhase.PRECHECKED), any());
        verify(taskMapper, never()).compareAndSetPhase(eq(3L), eq(JobPhase.SCHEMA_SNAPSHOT), any());
    }

    @Test
    void fullOnlyStopsAfterFullStub() {
        MigrationTask task = task(4L, JobPhase.PRECHECKED, JobState.STARTING, MigrationMode.FULL_ONLY);
        stubTask(task);
        engines.schema.finish.countDown();
        TaskJob job = job(task);
        job.run();

        verify(taskMapper).compareAndSetPhase(4L, JobPhase.SCHEMA_SNAPSHOT, JobPhase.FULL);
        verify(taskMapper, never()).compareAndSetPhase(eq(4L), eq(JobPhase.FULL), eq(JobPhase.INCREMENTAL));
        assertThat(engines.incrementalCreated).isFalse();
        verify(taskMapper).compareAndSetJobState(4L, JobState.RUNNING, JobState.STOPPED);
    }

    private void stubTask(MigrationTask task) {
        when(taskMapper.findById(task.getId())).thenAnswer(invocation -> {
            MigrationTask copy = task;
            if (copy.getJobPhase() == JobPhase.PRECHECKED
                    && copy.getJobState() == JobState.RUNNING) {
                // after STARTING→RUNNING CAS in run()
            }
            return copy;
        });
        when(taskMapper.compareAndSetPhase(eq(task.getId()), eq(JobPhase.PRECHECKED), eq(JobPhase.SCHEMA_SNAPSHOT)))
                .thenAnswer(invocation -> {
                    task.setJobPhase(JobPhase.SCHEMA_SNAPSHOT);
                    return 1;
                });
        when(taskMapper.compareAndSetPhase(eq(task.getId()), eq(JobPhase.SCHEMA_SNAPSHOT), eq(JobPhase.FULL)))
                .thenAnswer(invocation -> {
                    task.setJobPhase(JobPhase.FULL);
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
                offsetMapper,
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
        final FakeEngine schema = new FakeEngine(false);
        final FakeEngine incremental = new FakeEngine(true);
        volatile boolean schemaCreated;
        volatile boolean incrementalCreated;
        RuntimeException schemaFailure;

        @Override
        public CdcEngine createSchemaSnapshot(EngineSpec spec) {
            schemaCreated = true;
            if (schemaFailure != null) {
                schema.failure = schemaFailure;
            }
            return schema;
        }

        @Override
        public CdcEngine createIncremental(EngineSpec spec) {
            incrementalCreated = true;
            return incremental;
        }
    }

    static final class FakeEngine implements CdcEngine {
        final CountDownLatch runStarted = new CountDownLatch(1);
        final CountDownLatch finish = new CountDownLatch(1);
        final boolean block;
        volatile boolean stopCalled;
        RuntimeException failure;

        FakeEngine(boolean block) {
            this.block = block;
            if (!block) {
                finish.countDown();
            }
        }

        @Override
        public void run() {
            runStarted.countDown();
            if (failure != null) {
                throw failure;
            }
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
