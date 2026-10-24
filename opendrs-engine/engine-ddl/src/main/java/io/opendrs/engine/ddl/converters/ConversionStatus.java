package io.opendrs.engine.ddl.converters;

/**
 * DDL Conversion Status
 *
 */
public enum ConversionStatus {
    /**
     * Succeeded to convert sql statement from source database to target database
     */
    SUCCEEDED,
    /**
     * Failed to convert sql statement from source database to target database
     */
    FAILED
}
