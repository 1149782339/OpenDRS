package io.opendrs.engine.replay.naming;

import io.opendrs.common.naming.ColumnNamingStrategy;

import java.util.Locale;

public class LowCaseColumnNamingStrategy implements ColumnNamingStrategy {
    /**
     * Resolve column name from field name in sink record
     * {@link org.apache.kafka.connect.sink.SinkRecord}
     * convert it to lower case,
     *
     */
    @Override
    public String resolveColumnName(String column) {
        return column.toLowerCase(Locale.ROOT);
    }
}
