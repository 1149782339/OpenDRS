package io.opendrs.engine.schema.meta;

import io.opendrs.engine.common.metadata.TableId;
import java.util.Objects;

/**
 * Identity of one object in the schema-migration pipeline.
 *
 * <p>{@code schema} is always the schema name: the object's own name when {@code type} is
 * {@link ObjectType#SCHEMA}, and the containing schema for a table or view. {@code name} is the
 * object inside that schema, and is {@code null} for a schema — a schema has no table name, and
 * {@link TableId} is not a place to store one.
 */
public record ObjectId(ObjectType type, String schema, String name) {

    public ObjectId {
        Objects.requireNonNull(type, "type");
        if (schema == null || schema.isBlank()) {
            throw new IllegalArgumentException("schema is required");
        }
        if (type == ObjectType.SCHEMA) {
            name = null;
        } else if (name == null || name.isBlank()) {
            throw new IllegalArgumentException(type + " requires a name");
        }
    }

    public static ObjectId schema(String schema) {
        return new ObjectId(ObjectType.SCHEMA, schema, null);
    }

    public static ObjectId table(String schema, String table) {
        return new ObjectId(ObjectType.TABLE, schema, table);
    }

    public static ObjectId view(String schema, String view) {
        return new ObjectId(ObjectType.VIEW, schema, view);
    }

    /** Table or view coordinate. A schema object has no table. */
    public TableId tableId() {
        if (type == ObjectType.SCHEMA) {
            throw new IllegalStateException("schema object has no table id");
        }
        return TableId.of(schema, name);
    }
}
