package io.opendrs.engine.replay.relation;

import io.opendrs.common.metadata.TableId;

import io.opendrs.engine.replay.annotation.ThreadSafe;
import io.opendrs.engine.replay.dialect.DatabaseDialect;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;

/**
 * Table definitions
 *
 */
@ThreadSafe
public class TableDefinitions {

    private final Map<TableId, TableDefinition> tableDefinitionCache;
    private final DatabaseDialect databaseDialect;

    /**
     * Table Definitions
     *
     * @param databaseDialect {@link DatabaseDialect}
     */
    public TableDefinitions(DatabaseDialect databaseDialect) {
        this.databaseDialect = databaseDialect;
        this.tableDefinitionCache = new HashMap<>();
    }

    /**
     * Get table definition corresponding to the tableId
     *
     * @param connection {@link Connection}
     * @param tableId    {@link TableId}
     * @return table definition corresponding to the tableId  {@link TableDefinition}
     * @throws SQLException if there is an error using JDBC interface
     */
    public synchronized TableDefinition get(Connection connection, TableId tableId) throws SQLException {
        TableDefinition tableDefinition = tableDefinitionCache.get(tableId);
        if (tableDefinition != null) {
            return tableDefinition;
        }
        if (!databaseDialect.tableExists(connection, tableId)) {
            return null;
        }
        tableDefinition = databaseDialect.readTable(connection, tableId);
        if (tableDefinition == null) {
            return null;
        }
        tableDefinitionCache.put(tableId, tableDefinition);
        return tableDefinition;
    }

    public synchronized void refresh(Connection connection, TableId tableId) throws SQLException {
        tableDefinitionCache.remove(tableId);
        TableDefinition tableDefinition = databaseDialect.readTable(connection, tableId);
        if (tableDefinition == null) {
            return;
        }
        tableDefinitionCache.put(tableId, tableDefinition);
    }
}
