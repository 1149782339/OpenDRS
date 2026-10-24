package io.opendrs.engine.replay.mapping;

import io.opendrs.common.metadata.TableId;
import java.util.List;

/**
 * Which source tables a task covers, plus optional schema/table renames.
 *
 * <p>Leaving all four lists empty means <em>everything</em> the source connection can read — the
 * same thing Debezium does when neither {@code table.include.list} nor {@code database.include.list}
 * is set. That is the "migrate the whole instance" mode, and it deliberately keeps following
 * schemas and tables created after the task started; a resolved snapshot list could not.
 *
 * <p>Otherwise the scope is one of two shapes, never both:
 * <ul>
 *   <li>{@code schemas} — every table of those schemas (a whole MySQL database / PG schema);</li>
 *   <li>{@code tables} — exactly those tables.</li>
 * </ul>
 * Names are literals: the UI picks them from a list, so there is no pattern syntax to expand.
 * {@code excludeSchemas} / {@code excludeTables} are applied on top and always win.
 *
 * <p>Mixing the two shapes is rejected by {@link MappingValidator}: one Debezium connector cannot
 * express "this whole schema plus a couple of tables from another one" without a negative-lookahead
 * pattern, and a wrong pattern there silently captures or drops a whole database.
 *
 * <p>"Schema" is the neutral word for MySQL {@code database} and PostgreSQL {@code schema}. Every
 * {@link TableId} here is two-part ({@code schema.table}); any catalog component is dropped on the
 * way in so that a table selected through the UI compares equal to the same table read from JDBC
 * metadata.
 */
public record TableSelection(
        List<String> schemas,
        List<TableId> tables,
        List<String> excludeSchemas,
        List<TableId> excludeTables,
        TableMappings mappings) {

    public TableSelection {
        schemas = schemaNames(schemas);
        tables = tableIds(tables);
        excludeSchemas = schemaNames(excludeSchemas);
        excludeTables = tableIds(excludeTables);
    }

    /** Selection without excludes or mappings. */
    public TableSelection(List<String> schemas, List<TableId> tables) {
        this(schemas, tables, null, null, null);
    }

    /** No filter at all: every readable schema, including ones created later. */
    public static TableSelection all() {
        return new TableSelection(null, null, null, null, null);
    }

    /** Whole-schema selection. */
    public static TableSelection ofSchemas(List<String> schemas) {
        return new TableSelection(schemas, null, null, null, null);
    }

    /** Table-level selection. */
    public static TableSelection ofTables(List<TableId> tables) {
        return new TableSelection(null, tables, null, null, null);
    }

    /** True when no include list narrows the scope: "everything the source has". */
    public boolean isAll() {
        return schemas.isEmpty() && tables.isEmpty();
    }

    /** True when the scope is expressed as whole schemas (possibly the implicit "all"). */
    public boolean isSchemaLevel() {
        return tables.isEmpty();
    }

    public boolean includesWholeSchema(String schema) {
        return isAll() || containsName(schemas, schema);
    }

    public boolean isSchemaExcluded(String schema) {
        return containsName(excludeSchemas, schema);
    }

    public boolean isTableExcluded(String schema, String table) {
        for (TableId excluded : excludeTables) {
            if (excluded.getSchema().equalsIgnoreCase(schema) && excluded.getTable().equalsIgnoreCase(table)) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsName(List<String> names, String name) {
        for (String candidate : names) {
            if (candidate.equalsIgnoreCase(name)) {
                return true;
            }
        }
        return false;
    }

    private static List<String> schemaNames(List<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        return values.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(String::trim)
                .distinct()
                .toList();
    }

    private static List<TableId> tableIds(List<TableId> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        return values.stream()
                .filter(value -> value != null && !isBlank(value.getSchema()) && !isBlank(value.getTable()))
                .map(value -> TableId.of(value.getSchema().trim(), value.getTable().trim()))
                .distinct()
                .toList();
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
