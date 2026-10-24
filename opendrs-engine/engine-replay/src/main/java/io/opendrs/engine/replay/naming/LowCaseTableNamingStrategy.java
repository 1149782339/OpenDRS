package io.opendrs.engine.replay.naming;

import io.opendrs.common.naming.TableNamingStrategy;

import io.opendrs.common.metadata.TableId;

import java.util.Locale;

/**
 * Low case table naming strategy
 * source database with default uppercase  to target database with default lowercase
 *
 */
public class LowCaseTableNamingStrategy implements TableNamingStrategy {
    /**
     * Resolve table name from field name in sink record
     * {@link org.apache.kafka.connect.sink.SinkRecord}
     *
     */
    @Override
    public TableId resolveTableId(TableId tableId) {
        return null;
    }
}
