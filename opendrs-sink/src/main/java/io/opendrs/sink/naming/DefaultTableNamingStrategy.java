/*
 *  Copyright DbSink Authors.
 *  This source code is licensed under the Apache License Version 2.0, available
 *  at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.opendrs.sink.naming;

import io.opendrs.sink.relation.TableId;

/**
 * Default  table naming strategy without any changes
 *
 * @author Wang Wei
 * @time: 2023-06-24
 */
public class DefaultTableNamingStrategy implements TableNamingStrategy {
    /**
     * Resolve table name from field name in sink record
     * {@link org.apache.kafka.connect.sink.SinkRecord}
     *
     * @author Wang Wei
     * @time: 2023-06-24
     */
    @Override
    public TableId resolveTableId(TableId tableId) {
        return tableId;
    }
}
