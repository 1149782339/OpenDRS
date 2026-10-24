package io.opendrs.engine.replay.naming;

import io.opendrs.common.naming.ColumnNamingStrategy;

import java.util.Locale;

/**
 * Upper case column naming strategy
 * source database with default lowercase  to target database with default uppercase
 *
 */
public class UpperCaseColumnNamingStrategy implements ColumnNamingStrategy {
    /**
     * Resolve column name from field name in sink record
     * {@link org.apache.kafka.connect.sink.SinkRecord}
     *
     */
    @Override
    public String resolveColumnName(String column) {
        return column.toUpperCase(Locale.ROOT);
    }
}
