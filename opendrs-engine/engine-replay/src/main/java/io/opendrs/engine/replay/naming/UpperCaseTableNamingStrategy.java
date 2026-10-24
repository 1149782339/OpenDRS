package io.opendrs.engine.replay.naming;

import io.opendrs.common.naming.TableNamingStrategy;

import io.opendrs.common.metadata.TableId;

import java.util.Locale;
import java.util.Optional;

/**
 * Upper case table naming strategy
 * source database with default lowercase to target database with default uppercase
 *
 */
public class UpperCaseTableNamingStrategy implements TableNamingStrategy {
    /**
     * Resolve table name from field name in sink record
     * {@link org.apache.kafka.connect.sink.SinkRecord}
     *
     */
    @Override
    public TableId resolveTableId(TableId tableId) {
        String catalog = Optional
            .ofNullable(tableId.getCatalog()).map(item -> item.toUpperCase(Locale.ROOT))
            .orElse(null);
        String schema = Optional
            .ofNullable(tableId.getSchema()).map(item -> item.toUpperCase(Locale.ROOT))
            .orElse(null);
        String table = Optional
            .ofNullable(tableId.getTable()).map(item -> item.toUpperCase(Locale.ROOT))
            .orElse(null);
        return new TableId(catalog, schema, table);
    }
}
