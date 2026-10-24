package io.opendrs.migration.job;

import io.opendrs.engine.core.MigrationEngine;
import io.opendrs.migration.mapper.ConnectionInfoMapper;
import io.opendrs.migration.mapper.MigrationTaskMapper;
import io.opendrs.migration.mapper.TaskPipelineMapper;
import io.opendrs.migration.service.MigrationLagRegistry;
import io.opendrs.migration.service.MigrationLogService;
import io.opendrs.migration.service.ServerIdAllocator;
import org.springframework.stereotype.Component;

@Component
public class MigrationCoordinatorFactory {

    private final MigrationTaskMapper taskMapper;
    private final ConnectionInfoMapper connectionMapper;
    private final TaskPipelineMapper pipelineMapper;
    private final MigrationCoordinatorRegistry registry;
    private final MigrationEngine engine;
    private final MigrationLogService logService;
    private final MigrationLagRegistry lagRegistry;
    private final ServerIdAllocator serverIdAllocator;

    public MigrationCoordinatorFactory(
            MigrationTaskMapper taskMapper,
            ConnectionInfoMapper connectionMapper,
            TaskPipelineMapper pipelineMapper,
            MigrationCoordinatorRegistry registry,
            MigrationEngine engine,
            MigrationLogService logService,
            MigrationLagRegistry lagRegistry,
            ServerIdAllocator serverIdAllocator) {
        this.taskMapper = taskMapper;
        this.connectionMapper = connectionMapper;
        this.pipelineMapper = pipelineMapper;
        this.registry = registry;
        this.engine = engine;
        this.logService = logService;
        this.lagRegistry = lagRegistry;
        this.serverIdAllocator = serverIdAllocator;
    }

    public MigrationCoordinator create(io.opendrs.migration.domain.MigrationTask task) {
        return new MigrationCoordinator(
                task.getId(),
                task.getMode(),
                taskMapper,
                connectionMapper,
                registry,
                engine,
                logService.listenerFor(task.getId()),
                null,
                serverIdAllocator,
                lagRegistry::listenerFor,
                pipelineMapper);
    }
}
