package io.opendrs.migration.config;

import io.opendrs.jdbc.JdbcConnectionFactory;
import io.opendrs.jdbc.dialect.MysqlDialect;
import io.opendrs.jdbc.dialect.OracleDialect;
import io.opendrs.jdbc.dialect.PostgresDialect;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Registers the framework-free {@code opendrs-jdbc} mechanism classes as Spring beans.
 * The classes themselves stay annotation-free; the service owns their wiring.
 */
@Configuration
public class JdbcConfig {

    @Bean
    public JdbcConnectionFactory jdbcConnectionFactory() {
        return new JdbcConnectionFactory();
    }

    @Bean
    public MysqlDialect mysqlDialect() {
        return new MysqlDialect();
    }

    @Bean
    public PostgresDialect postgresDialect() {
        return new PostgresDialect();
    }

    @Bean
    public OracleDialect oracleDialect() {
        return new OracleDialect();
    }
}
