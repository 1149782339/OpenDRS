package io.opendrs.migration.job;

import io.opendrs.engine.core.MigrationEngine;
import io.opendrs.migration.mapper.ConnectionInfoMapper;
import io.opendrs.migration.mapper.MigrationTaskMapper;
import io.opendrs.migration.mapper.TaskPipelineMapper;
import io.opendrs.migration.service.MigrationLagRegistry;
import io.opendrs.migration.service.MigrationLogService;
import io.opendrs.migration.service.FullLoadResumePlanner;
import io.opendrs.migration.config.OpenDrsTransportProperties;
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
    private final FullLoadResumePlanner resumePlanner;
    private final OpenDrsTransportProperties transportProperties;
    private final io.opendrs.migration.service.deferred.DeferredObjectService deferredService;

    public MigrationCoordinatorFactory(
            MigrationTaskMapper taskMapper,
            ConnectionInfoMapper connectionMapper,
            TaskPipelineMapper pipelineMapper,
            MigrationCoordinatorRegistry registry,
            MigrationEngine engine,
            MigrationLogService logService,
            MigrationLagRegistry lagRegistry,
            ServerIdAllocator serverIdAllocator,
            FullLoadResumePlanner resumePlanner,
            OpenDrsTransportProperties transportProperties,
            io.opendrs.migration.service.deferred.DeferredObjectService deferredService) {
        this.taskMapper = taskMapper;
        this.connectionMapper = connectionMapper;
        this.pipelineMapper = pipelineMapper;
        this.registry = registry;
        this.engine = engine;
        this.logService = logService;
        this.lagRegistry = lagRegistry;
        this.serverIdAllocator = serverIdAllocator;
        this.resumePlanner = resumePlanner;
        this.transportProperties = transportProperties;
        this.deferredService = deferredService;
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
                pipelineId -> lagRegistry.listenerFor(pipelineId, task.getId()),
                pipelineMapper,
                resumePlanner,
                transportProperties,
                deferredService);
    }
}
