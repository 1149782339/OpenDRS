package io.opendrs.engine.schema.convert.converters;

import io.opendrs.engine.common.metadata.DatabaseType;
import io.opendrs.engine.schema.convert.ConversionConfiguration;

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
