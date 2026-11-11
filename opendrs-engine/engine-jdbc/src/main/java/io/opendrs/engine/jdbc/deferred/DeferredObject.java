package io.opendrs.engine.jdbc.deferred;

import java.util.Comparator;
import java.util.Objects;

/**
 * One captured secondary object of a target table, with everything needed to drop it and put it
 * back without reading the catalog again.
 *
 * @param schema target schema (PostgreSQL schema / MySQL database)
 * @param table target table
 * @param name object name as the catalog reports it (index or constraint name)
 * @param createDdl statement that recreates the object
 * @param dropDdl statement that drops the object
 * @param postDdl optional follow-up after a successful create (PostgreSQL {@code VALIDATE
 *     CONSTRAINT} for a foreign key added {@code NOT VALID}); null when there is none
 * @param buildOrder rebuild order inside one type (catalog order), ties broken by name
 */
public record DeferredObject(
        String schema,
        String table,
        DeferredObjectType type,
        String name,
        String createDdl,
        String dropDdl,
        String postDdl,
        int buildOrder) {

    /** Rebuild order: type rank (INDEX, UNIQUE, FOREIGN_KEY), then table, then build order. */
    public static final Comparator<DeferredObject> BUILD_ORDER = Comparator
            .comparingInt((DeferredObject o) -> o.type().buildRank())
            .thenComparing(DeferredObject::schema)
            .thenComparing(DeferredObject::table)
            .thenComparingInt(DeferredObject::buildOrder)
            .thenComparing(DeferredObject::name);

    /** Drop order: foreign keys first, then unique keys, then plain indexes. */
    public static final Comparator<DeferredObject> DROP_ORDER = Comparator
            .comparingInt((DeferredObject o) -> o.type().dropRank())
            .thenComparing(DeferredObject::schema)
            .thenComparing(DeferredObject::table)
            .thenComparingInt(DeferredObject::buildOrder)
            .thenComparing(DeferredObject::name);

    public DeferredObject {
        Objects.requireNonNull(schema, "schema");
        Objects.requireNonNull(table, "table");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(createDdl, "createDdl");
        Objects.requireNonNull(dropDdl, "dropDdl");
    }

    public String qualifiedTable() {
        return schema + "." + table;
    }

    @Override
    public String toString() {
        return type + " " + schema + "." + table + "." + name;
    }
}
