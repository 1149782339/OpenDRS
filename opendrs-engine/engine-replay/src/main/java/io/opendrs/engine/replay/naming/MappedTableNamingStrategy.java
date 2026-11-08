package io.opendrs.engine.replay.naming;

import io.opendrs.common.metadata.TableId;
import io.opendrs.common.naming.TableNamingStrategy;
import io.opendrs.engine.replay.mapping.MappingValidator;
import io.opendrs.common.selection.TableSelection;

/**
 * Maps MySQL source {@link TableId} (catalog=db) onto OpenDRS task mappings at the consumer
 * boundary. Sink {@link TableId} identity is unchanged.
 */
public final class MappedTableNamingStrategy implements TableNamingStrategy {

    private final MappingValidator mappingValidator;
    private final TableSelection tableSelection;

    public MappedTableNamingStrategy(MappingValidator mappingValidator, TableSelection tableSelection) {
        this.mappingValidator = mappingValidator;
        this.tableSelection = tableSelection;
    }

    @Override
    public TableId resolveTableId(TableId tableId) {
        if (tableId == null) {
            return tableId;
        }
        String sourceSchema = tableId.getSchema() != null && !tableId.getSchema().isBlank()
                ? tableId.getSchema()
                : tableId.getCatalog();
        String sourceTable = tableId.getTable();
        if (sourceSchema == null || sourceTable == null) {
            return new TableId(null, sourceSchema, sourceTable);
        }
        return mappingValidator.resolveTableId(tableSelection, sourceSchema, sourceTable);
    }
}
