package io.opendrs.datacheck.job;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.opendrs.datacheck.mapper.DataCheckTaskRepository;
import io.opendrs.engine.core.MigrationEngine;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class DataCheckCoordinatorFactory {

    private final DataCheckTaskRepository repository;
    private final MigrationEngine engine;
    private final DataCheckCoordinatorRegistry registry;
    private final ObjectMapper objectMapper;
    private final int concurrency;

    public DataCheckCoordinatorFactory(
            DataCheckTaskRepository repository,
            MigrationEngine engine,
            DataCheckCoordinatorRegistry registry,
            ObjectMapper objectMapper,
            @Value("${opendrs.datacheck.concurrency:8}") int concurrency) {
        this.repository = repository;
        this.engine = engine;
        this.registry = registry;
        this.objectMapper = objectMapper;
        this.concurrency = concurrency;
    }

    public DataCheckCoordinator create(long taskId) {
        return new DataCheckCoordinator(
                taskId, repository, engine, registry, objectMapper, concurrency);
    }
}
