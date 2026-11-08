package io.opendrs.datacheck.config;

import io.opendrs.engine.core.DefaultMigrationEngine;
import io.opendrs.engine.core.MigrationEngine;
import io.opendrs.engine.jdbc.JdbcConnectionFactory;
import io.opendrs.engine.replay.mapping.MappingValidator;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import javax.sql.DataSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Wires the framework-free engine classes and the coordinator pool used by this service. */
@Configuration
public class DataCheckEngineConfig {

    @Bean
    public JdbcConnectionFactory jdbcConnectionFactory() {
        return new JdbcConnectionFactory();
    }

    @Bean
    public MigrationEngine migrationEngine(
            JdbcConnectionFactory connectionFactory, DataSource dataSource) {
        return new DefaultMigrationEngine(connectionFactory, dataSource);
    }

    @Bean
    public MappingValidator mappingValidator() {
        return new MappingValidator();
    }

    @Bean(destroyMethod = "shutdownNow")
    public ExecutorService dataCheckCoordinatorExecutor() {
        return Executors.newFixedThreadPool(4, runnable -> {
            Thread thread = new Thread(runnable, "datacheck-coordinator");
            thread.setDaemon(true);
            return thread;
        });
    }
}
