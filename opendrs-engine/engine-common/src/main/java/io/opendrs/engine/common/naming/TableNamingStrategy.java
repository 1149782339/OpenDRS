package io.opendrs.engine.common.naming;

import io.opendrs.engine.common.metadata.TableId;

/**
 * Table naming strategy, used to resolve the table name in the target database
 * from the source table coordinate.
 *
 */
public interface TableNamingStrategy {
    /**
     * Resolve the source table identifier into the target table identifier.
     *
     * @param tableId source table identifier {@link TableId}
     * @return target table identifier {@link TableId}
     */
    TableId resolveTableId(TableId tableId);
}
