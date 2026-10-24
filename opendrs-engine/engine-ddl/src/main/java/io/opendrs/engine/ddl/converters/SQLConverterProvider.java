package io.opendrs.engine.ddl.converters;

import io.opendrs.common.metadata.DatabaseType;

/**
 * SQL converter provider
 *
 */
public interface SQLConverterProvider {
    /**
     * Source database of the SQL converter
     *
     * @return database type {@link DatabaseType}
     */
    DatabaseType sourceDatabase();

    /**
     * Target database of the SQL converter
     *
     * @return database type {@link DatabaseType}
     */
    DatabaseType targetDatabase();

    /**
     * Create SQL converter
     *
     * @param configuration {@link ConversionConfiguration}
     * @return SQL converter {@link SQLConverter}
     */
    SQLConverter create(ConversionConfiguration configuration);
}
