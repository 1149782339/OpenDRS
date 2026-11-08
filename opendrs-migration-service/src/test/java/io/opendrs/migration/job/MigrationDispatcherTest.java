package io.opendrs.migration.job;

import java.util.UUID;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.opendrs.migration.domain.MigrationPhase;
import io.opendrs.engine.core.TaskState;
import io.opendrs.migration.domain.MigrationMode;
import io.opendrs.migration.domain.MigrationTask;
import io.opendrs.migration.mapper.MigrationTaskMapper;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

class MigrationDispatcherTest {

    private MigrationTaskMapper taskMapper;
    private MigrationCoordinatorRegistry registry;
    private MigrationCoordinatorFactory factory;
    private ThreadPoolTaskExecutor executor;
    private MigrationDispatcher dispatcher;

    @BeforeEach
    void setUp() {
        taskMapper = Mockito.mock(MigrationTaskMapper.class);
        registry = Mockito.mock(MigrationCoordinatorRegistry.class);
        factory = Mockito.mock(MigrationCoordinatorFactory.class);
        executor = Mockito.mock(ThreadPoolTaskExecutor.class);
        when(factory.create(any(MigrationTask.class))).thenAnswer(invocation -> {
            MigrationTask task = invocation.getArgument(0);
            return new MigrationCoordinator(task.getId(), task.getMode(), taskMapper, registry);
        });
        dispatcher = new MigrationDispatcher(taskMapper, registry, factory, executor);
    }

    @Test
    void startingWithoutRegistrySubmitsCoordinator() {
        MigrationTask task = dispatchable(1L, TaskState.STARTING);
        when(taskMapper.findDispatchable()).thenReturn(List.of(task));
        when(registry.hasLive(1L)).thenReturn(false);
        when(registry.tryRegister(any(MigrationCoordinator.class))).thenReturn(true);
        when(taskMapper.findById(1L)).thenReturn(task);

        dispatcher.dispatch();

        verify(executor).execute(any(MigrationCoordinator.class));
    }

    @Test
    void doesNotStartIfRereadStopped() {
        MigrationTask starting = dispatchable(2L, TaskState.STARTING);
        MigrationTask stopped = dispatchable(2L, TaskState.STOPPED);
        when(taskMapper.findDispatchable()).thenReturn(List.of(starting));
        when(registry.hasLive(2L)).thenReturn(false);
        when(registry.tryRegister(any(MigrationCoordinator.class))).thenReturn(true);
        when(taskMapper.findById(2L)).thenReturn(stopped);

        dispatcher.dispatch();

        verify(executor, never()).execute(any());
        verify(registry).remove(eq(2L), any(MigrationCoordinator.class));
    }

    @Test
    void skipsWhenRegistryAlreadyHasLiveJob() {
        MigrationTask task = dispatchable(3L, TaskState.RUNNING);
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

    private static MigrationTask dispatchable(long id, TaskState jobState) {
        MigrationTask task = new MigrationTask();
        task.setPublicId(UUID.randomUUID().toString());
        task.setId(id);
        task.setMode(MigrationMode.FULL_AND_INCREMENTAL);
        task.setPhase(MigrationPhase.PRECHECKED);
        task.setState(jobState);
        return task;
    }
}
