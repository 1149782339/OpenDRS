package io.opendrs.debezium;

import io.opendrs.jdbc.metadata.TableRef;
import io.opendrs.migration.api.request.TableSelection;
import io.opendrs.migration.service.MappingValidator;
import io.opendrs.sink.naming.TableNamingStrategy;
import io.opendrs.sink.relation.TableId;

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
        TableRef target = mappingValidator.resolveRef(tableSelection, sourceSchema, sourceTable);
        return new TableId(null, target.schema(), target.table());
    }
}
