package io.opendrs.migration.service;

import io.opendrs.common.metadata.TableId;
import io.opendrs.migration.api.request.SchemaMapping;
import io.opendrs.migration.api.request.SelectedTable;
import io.opendrs.migration.api.request.TableMapping;
import io.opendrs.migration.api.request.TableMappings;
import io.opendrs.migration.api.request.TableSelection;
import java.util.List;

/**
 * Service boundary projection: REST selection DTOs → engine-neutral mapping types
 * ({@code io.opendrs.engine.replay.mapping}). The engine never sees the REST layer, and the REST
 * layer never sees the engine's selection model — the two are shaped for their own callers and
 * meet here only.
 */
public final class MappingRequests {

    private MappingRequests() {
    }

    /**
     * Returns {@code null} for a null selection, which the engine reads as "no filter" — the whole
     * source. The REST contract requires the object itself ({@code @NotNull} on the create
     * request), so a client that wants everything sends four empty lists rather than omitting it.
     */
    public static io.opendrs.engine.replay.mapping.TableSelection toNeutral(TableSelection selection) {
        if (selection == null) {
            return null;
        }
        return new io.opendrs.engine.replay.mapping.TableSelection(
                selection.schemas(),
                tables(selection.tables()),
                selection.excludeSchemas(),
                tables(selection.excludeTables()),
                toNeutral(selection.mappings()));
    }

    private static List<TableId> tables(List<SelectedTable> tables) {
        if (tables == null) {
            return null;
        }
        return tables.stream()
                .filter(table -> table != null && table.schema() != null && table.table() != null)
                .map(table -> TableId.of(table.schema(), table.table()))
                .toList();
    }

    private static io.opendrs.engine.replay.mapping.TableMappings toNeutral(TableMappings mappings) {
        if (mappings == null) {
            return null;
        }
        List<io.opendrs.engine.replay.mapping.SchemaMapping> schema = mappings.schema() == null
                ? null
                : mappings.schema().stream().map(MappingRequests::toNeutral).toList();
        List<io.opendrs.engine.replay.mapping.TableMapping> tables = mappings.tables() == null
                ? null
                : mappings.tables().stream().map(MappingRequests::toNeutral).toList();
        return new io.opendrs.engine.replay.mapping.TableMappings(schema, tables);
    }

    private static io.opendrs.engine.replay.mapping.SchemaMapping toNeutral(SchemaMapping mapping) {
        return new io.opendrs.engine.replay.mapping.SchemaMapping(mapping.source(), mapping.target());
    }

    private static io.opendrs.engine.replay.mapping.TableMapping toNeutral(TableMapping mapping) {
        return new io.opendrs.engine.replay.mapping.TableMapping(
                mapping.sourceSchema(), mapping.sourceTable(), mapping.targetSchema(), mapping.targetTable());
    }
}
