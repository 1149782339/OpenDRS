package io.opendrs.engine.schema.convert;

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
    FAILED,
    /**
     * The source object has no target counterpart and the converter dropped it (1 to 0).
     * Statements are empty; the reason is carried in warnings.
     */
    DROPPED,
    /**
     * The target DDL was edited/accepted by a human after conversion. Import executes it
     * as-is without converting again. Converters never emit this; it is set when an edited
     * conversion document is saved.
     */
    MANUAL
}
