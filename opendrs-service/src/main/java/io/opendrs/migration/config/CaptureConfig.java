package io.opendrs.migration.config;

import io.micrometer.core.instrument.MeterRegistry;
import io.opendrs.engine.core.DefaultMigrationEngine;
import io.opendrs.engine.core.MigrationEngine;
import io.opendrs.jdbc.JdbcConnectionFactory;
import io.opendrs.jdbc.dialect.MysqlDialect;
import io.opendrs.jdbc.dialect.PostgresDialect;
import io.opendrs.engine.replay.mapping.MappingValidator;
import io.opendrs.migration.metrics.MicrometerReplayMetrics;
import javax.sql.DataSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Registers the framework-free engine classes as beans, mirroring {@link JdbcConfig}: the engine
 * modules carry no Spring annotations, so the service wires the instances it calls.
 */
@Configuration
public class CaptureConfig {

    /** Used directly by task creation validation; also passed into the engine. */
    @Bean
    public MappingValidator mappingValidator() {
        return new MappingValidator();
    }

    /**
     * The single engine entry point used by precheck orchestration and task jobs. The engine gets
     * the mechanism collaborators it cannot create itself (connections, dialects, metadata DB).
     * {@code destroyMethod} shuts the engine's worker pool down with the context.
     */
    @Bean(destroyMethod = "close")
    public MigrationEngine migrationEngine(
            JdbcConnectionFactory connectionFactory,
            MysqlDialect mysqlDialect,
            PostgresDialect postgresDialect,
            DataSource dataSource,
            MeterRegistry meterRegistry) {
        return new DefaultMigrationEngine(
                connectionFactory,
                mysqlDialect,
                postgresDialect,
                dataSource,
                new MicrometerReplayMetrics(meterRegistry));
    }
}
