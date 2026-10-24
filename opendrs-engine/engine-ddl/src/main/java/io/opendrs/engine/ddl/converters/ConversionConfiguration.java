package io.opendrs.engine.ddl.converters;

import io.opendrs.common.naming.ColumnNamingStrategy;
import io.opendrs.common.naming.TableNamingStrategy;

/**
 * Conversion configuration used by sql converter
 *
 */
public class ConversionConfiguration {
    private ColumnNamingStrategy columnNamingStrategy;
    private TableNamingStrategy tableNamingStrategy;

    public ConversionConfiguration(TableNamingStrategy tableNamingStrategy, ColumnNamingStrategy columnNamingStrategy) {
        this.tableNamingStrategy = tableNamingStrategy;
        this.columnNamingStrategy = columnNamingStrategy;
    }

    public ColumnNamingStrategy getColumnNamingStrategy() {
        return columnNamingStrategy;
    }

    public TableNamingStrategy getTableNamingStrategy() {
        return tableNamingStrategy;
    }
}
