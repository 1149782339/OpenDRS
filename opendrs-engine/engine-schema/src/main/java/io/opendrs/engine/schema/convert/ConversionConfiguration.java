package io.opendrs.engine.schema.convert;

import io.opendrs.common.metadata.DatabaseType;
import io.opendrs.common.naming.ColumnNamingStrategy;
import io.opendrs.common.naming.TableNamingStrategy;

/**
 * Conversion configuration used by sql converter
 *
 */
public class ConversionConfiguration {
    private ColumnNamingStrategy columnNamingStrategy;
    private TableNamingStrategy tableNamingStrategy;
    private DatabaseType sourceDatabase;
    private DatabaseType targetDatabase;

    public ConversionConfiguration(TableNamingStrategy tableNamingStrategy, ColumnNamingStrategy columnNamingStrategy) {
        this.tableNamingStrategy = tableNamingStrategy;
        this.columnNamingStrategy = columnNamingStrategy;
    }

    public ConversionConfiguration(
            TableNamingStrategy tableNamingStrategy,
            ColumnNamingStrategy columnNamingStrategy,
            DatabaseType sourceDatabase,
            DatabaseType targetDatabase) {
        this.tableNamingStrategy = tableNamingStrategy;
        this.columnNamingStrategy = columnNamingStrategy;
        this.sourceDatabase = sourceDatabase;
        this.targetDatabase = targetDatabase;
    }

    public ColumnNamingStrategy getColumnNamingStrategy() {
        return columnNamingStrategy;
    }

    public TableNamingStrategy getTableNamingStrategy() {
        return tableNamingStrategy;
    }

    public DatabaseType getSourceDatabase() {
        return sourceDatabase;
    }

    public DatabaseType getTargetDatabase() {
        return targetDatabase;
    }
}
