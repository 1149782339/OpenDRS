package io.opendrs.datacheck.job;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.opendrs.datacheck.mapper.DataCheckTaskRepository;
import io.opendrs.engine.core.MigrationEngine;
import io.opendrs.engine.jdbc.JdbcConnectionFactory;
import io.opendrs.migration.config.OpenDrsTransportProperties;
import io.opendrs.migration.service.MigrationLagRegistry;
import io.opendrs.migration.mapper.TaskPipelineMapper;
import java.time.Duration;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class DataCheckCoordinatorFactory {

    private final DataCheckTaskRepository repository;
    private final MigrationEngine engine;
    private final DataCheckCoordinatorRegistry registry;
    private final ObjectMapper objectMapper;
    private final int concurrency;
    private final TaskPipelineMapper pipelineMapper;
    private final OpenDrsTransportProperties transportProperties;
    private final DataSource metadataDataSource;
    private final JdbcConnectionFactory connectionFactory;
    private final Duration waitTimeout;
    private final Duration waitPollInterval;
    private final MigrationLagRegistry lagRegistry;

    public DataCheckCoordinatorFactory(
            DataCheckTaskRepository repository,
            MigrationEngine engine,
            DataCheckCoordinatorRegistry registry,
            ObjectMapper objectMapper,
            @Value("${opendrs.datacheck.concurrency:8}") int concurrency,
            TaskPipelineMapper pipelineMapper,
            OpenDrsTransportProperties transportProperties,
            DataSource metadataDataSource,
            JdbcConnectionFactory connectionFactory,
            @Value("${opendrs.datacheck.wait-for-target-timeout:5m}") Duration waitTimeout,
            @Value("${opendrs.datacheck.wait-for-target-poll:1s}") Duration waitPollInterval,
            MigrationLagRegistry lagRegistry) {
        this.repository = repository;
        this.engine = engine;
        this.registry = registry;
        this.objectMapper = objectMapper;
        this.concurrency = concurrency;
        this.pipelineMapper = pipelineMapper;
        this.transportProperties = transportProperties;
        this.metadataDataSource = metadataDataSource;
        this.connectionFactory = connectionFactory;
        this.waitTimeout = waitTimeout;
        this.waitPollInterval = waitPollInterval;
        this.lagRegistry = lagRegistry;
    }

    public DataCheckCoordinator create(long taskId) {
        return new DataCheckCoordinator(
                taskId, repository, engine, registry, objectMapper, concurrency,
                pipelineMapper, transportProperties, metadataDataSource, connectionFactory,
                waitTimeout, waitPollInterval, lagRegistry);
    }
}
