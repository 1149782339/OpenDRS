package io.opendrs.engine.replay.naming;

import io.opendrs.common.naming.TableNamingStrategy;

import io.opendrs.common.metadata.TableId;

/**
 * Default  table naming strategy without any changes
 *
 */
public class DefaultTableNamingStrategy implements TableNamingStrategy {
    /**
     * Resolve table name from field name in sink record
     * {@link org.apache.kafka.connect.sink.SinkRecord}
     *
     */
    @Override
    public TableId resolveTableId(TableId tableId) {
        return tableId;
    }
}
