package io.opendrs.engine.schema.convert.converters;

import io.opendrs.engine.schema.convert.ConversionResult;

/**
 * SQL converter
 *
 */
public interface SQLConverter {

    /**
     * Convert sql statement from source database to target database
     *
     * @param statement sql statement
     * @return SQL conversion result {@link ConversionResult}
     */
    ConversionResult convert(String statement);
}
