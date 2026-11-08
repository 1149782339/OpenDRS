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

    /**
     * The single namespace level a table belongs to: the schema when the driver has one, otherwise
     * the catalog. MySQL reports the database as the catalog and has no schema; PostgreSQL and
     * Oracle report it as the schema. Comparing this level makes the two spellings equal.
     */
    private String namespace() {
        if (schema != null && !schema.isEmpty()) {
            return schema;
        }
        return catalog != null && !catalog.isEmpty() ? catalog : null;
    }

    /**
     * Two identifiers are equal when they name the same {@code namespace.table}. The catalog is
     * ignored when a schema is present, so a three-part spelling from a Debezium source event
     * ({@code database.owner.table}) equals the two-part selection spelling ({@code owner.table}).
     *
     * <p>Table names are compared as-is: a quoted name containing a dot stays distinct from a
     * schema+table split that renders the same way.
     */
    @Override
    public boolean equals(Object o) {
        if (!(o instanceof TableId other)) {
            return false;
        }
        return Objects.equals(namespace(), other.namespace()) && Objects.equals(table, other.table);
    }

    @Override
    public int hashCode() {
        return Objects.hash(namespace(), table);
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
