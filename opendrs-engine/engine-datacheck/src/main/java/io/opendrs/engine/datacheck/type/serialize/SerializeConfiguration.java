package io.opendrs.engine.datacheck.type.serialize;

import io.opendrs.engine.common.metadata.DatabaseType;
import io.opendrs.engine.common.metadata.TableDefinition;

/**
 * Table-scoped serialization rules: what the check is comparing and which side it came from.
 * Immutable, so one instance can be shared by every row and every worker of a table.
 *
 * <p>{@code columnIndex} is expected to address the source and the target column definitions at
 * the same position, so the two column lists must be parallel.
 */
public final class SerializeConfiguration {

    private final TableDefinition sourceTable;
    private final TableDefinition targetTable;
    private final boolean includeFloat;
    private final DatabaseType sourceDatabase;
    private final DatabaseType targetDatabase;

    public SerializeConfiguration(
            TableDefinition sourceTable,
            TableDefinition targetTable,
            boolean includeFloat,
            DatabaseType sourceDatabase,
            DatabaseType targetDatabase) {
        this.sourceTable = sourceTable;
        this.targetTable = targetTable;
        this.includeFloat = includeFloat;
        this.sourceDatabase = sourceDatabase;
        this.targetDatabase = targetDatabase;
    }

    public TableDefinition sourceTable() {
        return sourceTable;
    }

    public TableDefinition targetTable() {
        return targetTable;
    }

    public boolean includeFloat() {
        return includeFloat;
    }

    public DatabaseType sourceDatabase() {
        return sourceDatabase;
    }

    public DatabaseType targetDatabase() {
        return targetDatabase;
    }
}
