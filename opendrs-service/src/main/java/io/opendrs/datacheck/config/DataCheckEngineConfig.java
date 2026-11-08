package io.opendrs.datacheck.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Data-check specific wiring. The engine collaborators shared with migration
 * ({@code JdbcConnectionFactory}, {@code MigrationEngine}, {@code MappingValidator}) are declared
 * once in {@code io.opendrs.migration.config} and injected here.
 */
@Configuration
public class DataCheckEngineConfig {

    /**
     * Jackson 2 mapper for the persisted options JSON. Spring Boot 4 auto-configures Jackson 3
     * ({@code tools.jackson}), which is a different type, so this service declares the mapper it
     * actually uses (the migration task options are written/read with Jackson 2).
     */
    @Bean
    public ObjectMapper objectMapper() {
        return new ObjectMapper();
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
