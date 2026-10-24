package io.opendrs.engine.replay.relation;

import io.opendrs.common.metadata.TableId;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Table definition
 *
 */
public class TableDefinition {
    private final TableId tableId;

    private final List<ColumnDefinition> columnDefinitions;

    private List<String> primaryKeyColumNames;

    private final Map<String, ColumnDefinition> columnDefinitionMap;

    private TableDefinition(TableId tableId, List<ColumnDefinition> columnDefinitions, List<String> primaryKeyColumNames) {
        this.tableId = tableId;
        this.columnDefinitions = columnDefinitions;
        this.columnDefinitionMap = columnDefinitions.stream()
                .collect(Collectors.toMap(ColumnDefinition::getName, Function.identity()));
        this.primaryKeyColumNames = primaryKeyColumNames;
    }

    public List<String> getPrimaryKeyColumNames() {
        return primaryKeyColumNames;
    }

    public TableId getTableId() {
        return tableId;
    }

    public List<ColumnDefinition> getColumnDefinitions() {
        return columnDefinitions;
    }

    public ColumnDefinition getColumnDefinition(String columnName) {
        return columnDefinitionMap.get(columnName);
    }
    public static Builder builder() {
        return new Builder();
    }
    public static final class Builder {
        private TableId tableId;
        private List<ColumnDefinition> columnDefinitions;
        private List<String> primaryKeyColumNames;
        private Map<String, ColumnDefinition> columnDefinitionMap;

        private Builder() {
        }

        public Builder tableId(TableId tableId) {
            this.tableId = tableId;
            return this;
        }

        public Builder columnDefinitions(List<ColumnDefinition> columnDefinitions) {
            this.columnDefinitions = columnDefinitions;
            return this;
        }

        public Builder primaryKeyColumNames(List<String> primaryKeyColumNames) {
            this.primaryKeyColumNames = primaryKeyColumNames;
            return this;
        }

        public TableDefinition build() {
            TableDefinition tableDefinition = new TableDefinition(tableId, columnDefinitions, primaryKeyColumNames);
            return tableDefinition;
        }
    }
}
