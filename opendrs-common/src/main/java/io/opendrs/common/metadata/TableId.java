package io.opendrs.common.metadata;

import io.opendrs.common.util.StringUtil;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Table identifier. A pure value type shared across modules (ddl conversion, jdbc metadata,
 * sink applier); SQL rendering lives in the consumers.
 *
 */
public class TableId {

    private final String catalog;
    private final String schema;
    private final String table;

    private final String id;

    public TableId(String catalog, String schema, String table) {
        this.catalog = catalog;
        this.schema = schema;
        this.table = table;
        this.id = tableId(catalog, schema, table);
    }

    /**
     * Two-part coordinate (schema + table), catalog left {@code null}.
     */
    public static TableId of(String schema, String table) {
        return new TableId(null, schema, table);
    }

    public String getCatalog() {
        return catalog;
    }

    public String getSchema() {
        return schema;
    }

    public String getTable() {
        return table;
    }

    public TableId toQuoted(char quotingChar) {
        String catalogName = null;
        if (this.catalog != null && !this.catalog.isEmpty()) {
            catalogName = StringUtil.quote(this.catalog, quotingChar);
        }

        String schemaName = null;
        if (this.schema != null && !this.schema.isEmpty()) {
            schemaName = StringUtil.quote(this.schema, quotingChar);
        }

        return new TableId(catalogName, schemaName, StringUtil.quote(this.table, quotingChar));
    }

    private static String tableId(String catalog, String schema, String table) {
        if (catalog == null || catalog.length() == 0) {
            if (schema == null || schema.length() == 0) {
                return table;
            }
            return schema + "." + table;
        }
        if (schema == null || schema.length() == 0) {
            return catalog + "." + table;
        }
        return catalog + "." + schema + "." + table;
    }

    public String identifier() {
        return id;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        TableId tableId = (TableId) o;
        return Objects.equals(catalog, tableId.catalog) && Objects.equals(schema, tableId.schema) && table.equals(tableId.table);
    }

    @Override
    public int hashCode() {
        return Objects.hash(catalog, schema, table);
    }

    @Override
    public String toString() {
        List<String> parts = new ArrayList<>(3);
        if (catalog != null) {
            parts.add(catalog);
        }
        if (schema != null) {
            parts.add(schema);
        }
        if (table != null) {
            parts.add(table);
        }
        return String.join(".", parts);
    }
}
