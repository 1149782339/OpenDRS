package io.opendrs.common.selection;

import java.util.List;

/** Schema-level and table-level mappings of a {@link TableSelection}. */
public record TableMappings(List<SchemaMapping> schema, List<TableMapping> tables) {
}
