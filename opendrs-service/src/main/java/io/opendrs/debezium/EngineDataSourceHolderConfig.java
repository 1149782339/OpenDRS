package io.opendrs.debezium;

import javax.sql.DataSource;
import org.springframework.context.annotation.Configuration;

@Configuration
public class EngineDataSourceHolderConfig {

    public EngineDataSourceHolderConfig(DataSource dataSource) {
        EngineDataSourceHolder.initialize(dataSource);
    }
}
