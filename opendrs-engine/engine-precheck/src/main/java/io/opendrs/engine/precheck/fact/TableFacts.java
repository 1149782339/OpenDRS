package io.opendrs.engine.precheck.fact;

import io.opendrs.common.metadata.TableId;

/**
 * What one table looks like on one endpoint. Collected in bulk (one query per endpoint, not one per
 * table) because the checks that need it - unsupported engines, missing primary keys, target name
 * clashes - all cover every selected table.
 */
public record TableFacts(TableId ref, boolean exists, String engine, boolean primaryKey) {

    /** A table that was selected but does not exist on this endpoint. */
    public static TableFacts missing(TableId ref) {
        return new TableFacts(ref, false, null, false);
    }

    public String qualifiedName() {
        return ref.getSchema() + "." + ref.getTable();
    }
}
