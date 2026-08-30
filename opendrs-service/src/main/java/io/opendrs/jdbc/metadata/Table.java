package io.opendrs.jdbc.metadata;

/** Catalog object for future column/PK metadata. {@code ref} is the coordinate. */
public final class Table {

    private final TableRef ref;

    public Table(TableRef ref) {
        this.ref = ref;
    }

    public TableRef ref() {
        return ref;
    }
}
