package io.opendrs.migration.job;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.opendrs.migration.domain.JobPhase;
import io.opendrs.migration.domain.JobState;
import io.opendrs.migration.domain.MigrationMode;
import io.opendrs.migration.domain.MigrationTask;
import io.opendrs.migration.mapper.MigrationTaskMapper;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

class JobDispatcherTest {

    private MigrationTaskMapper taskMapper;
    private TaskJobRegistry registry;
    private ThreadPoolTaskExecutor executor;
    private JobDispatcher dispatcher;

    @BeforeEach
    void setUp() {
        taskMapper = Mockito.mock(MigrationTaskMapper.class);
        registry = Mockito.mock(TaskJobRegistry.class);
        executor = Mockito.mock(ThreadPoolTaskExecutor.class);
        dispatcher = new JobDispatcher(taskMapper, registry, executor);
    }

    @Test
    void startingWithoutRegistrySubmitsCoordinator() {
        MigrationTask task = dispatchable(1L, JobState.STARTING);
        when(taskMapper.findDispatchable()).thenReturn(List.of(task));
        when(registry.hasLive(1L)).thenReturn(false);
        when(registry.tryRegister(any(TaskJob.class))).thenReturn(true);
        when(taskMapper.findById(1L)).thenReturn(task);

        dispatcher.dispatch();

        verify(executor).execute(any(TaskJob.class));
    }

    @Test
    void doesNotStartIfRereadStopped() {
        MigrationTask starting = dispatchable(2L, JobState.STARTING);
        MigrationTask stopped = dispatchable(2L, JobState.STOPPED);
        when(taskMapper.findDispatchable()).thenReturn(List.of(starting));
        when(registry.hasLive(2L)).thenReturn(false);
        when(registry.tryRegister(any(TaskJob.class))).thenReturn(true);
        when(taskMapper.findById(2L)).thenReturn(stopped);

        dispatcher.dispatch();

        verify(executor, never()).execute(any());
        verify(registry).remove(eq(2L), any(TaskJob.class));
    }

    @Test
    void skipsWhenRegistryAlreadyHasLiveJob() {
        MigrationTask task = dispatchable(3L, JobState.RUNNING);
        when(taskMapper.findDispatchable()).thenReturn(List.of(task));
        when(registry.hasLive(3L)).thenReturn(true);

        dispatcher.dispatch();

        verify(registry, never()).tryRegister(any());
        verify(executor, never()).execute(any());
    }

    @Test
    void bootMarksStoppingAsStopped() {
        when(taskMapper.markStoppingAsStopped()).thenReturn(2);
        dispatcher.run(null);
        verify(taskMapper).markStoppingAsStopped();
    }

    private static MigrationTask dispatchable(long id, JobState jobState) {
        MigrationTask task = new MigrationTask();
        task.setId(id);
        task.setMode(MigrationMode.FULL_AND_INCREMENTAL);
        task.setJobPhase(JobPhase.PRECHECKED);
        task.setJobState(jobState);
        return task;
    }
}
