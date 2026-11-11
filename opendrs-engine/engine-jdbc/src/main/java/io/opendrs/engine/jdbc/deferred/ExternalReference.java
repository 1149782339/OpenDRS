package io.opendrs.engine.jdbc.deferred;

/**
 * A foreign key on a table <em>outside</em> the task that references a table inside it. It is
 * never dropped (the task does not own the child), so the precheck reports it: it keeps the
 * referenced key in place and, on PostgreSQL, blocks {@code TRUNCATE} of the parent.
 */
public record ExternalReference(
        String childSchema,
        String childTable,
        String constraintName,
        String parentSchema,
        String parentTable) {

    @Override
    public String toString() {
        return childSchema + "." + childTable + "." + constraintName
                + " -> " + parentSchema + "." + parentTable;
    }
}
