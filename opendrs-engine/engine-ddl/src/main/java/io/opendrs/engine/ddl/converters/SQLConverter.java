package io.opendrs.engine.ddl.converters;

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
