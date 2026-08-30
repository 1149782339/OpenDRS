package io.opendrs.debezium;

import javax.sql.DataSource;

/**
 * Process-wide metadata {@link DataSource} for Engine-created stores. The Engine is not a Spring
 * bean and instantiates {@link TaskOffsetBackingStore} / {@link TaskSchemaHistory} with a no-arg
 * constructor.
 */
public final class EngineDataSourceHolder {

    private static volatile DataSource dataSource;

    private EngineDataSourceHolder() {
    }

    public static void initialize(DataSource source) {
        dataSource = source;
    }

    public static DataSource get() {
        DataSource source = dataSource;
        if (source == null) {
            throw new IllegalStateException("Engine DataSource has not been initialized");
        }
        return source;
    }
}
