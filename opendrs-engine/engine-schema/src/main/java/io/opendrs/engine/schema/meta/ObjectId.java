package io.opendrs.engine.schema.meta;

import io.opendrs.common.metadata.TableId;

/**
 * Identity of a database object in the schema-migration pipeline: the two-part {@link TableId}
 * shared with selection and the DDL converter, plus the object category the id refers to.
 */
public record ObjectId(TableId tableId, ObjectType type) {

}
