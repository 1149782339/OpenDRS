package io.opendrs.engine.jdbc.deferred;

/**
 * Secondary target objects that are taken off the target before the full load and put back
 * afterwards. The declaration order is the rebuild order: plain indexes first (right after the
 * full load, before replay), then unique indexes, then foreign keys (both only once replay has
 * passed the snapshot end position P1). Drops run in reverse: foreign keys first.
 */
public enum DeferredObjectType {

    /** Plain (non-unique) secondary index. Rebuilt after the full load, before replay starts. */
    INDEX,

    /** Unique index or unique constraint (not the primary key). Rebuilt after replay passed P1. */
    UNIQUE,

    /** Foreign key constraint. Rebuilt after the unique keys it may depend on. */
    FOREIGN_KEY;

    /** Rebuild rank: lower builds first. Persisted as {@code build_order}. */
    public int buildRank() {
        return ordinal();
    }

    /** Drop rank: lower drops first, so foreign keys go before the indexes they may need. */
    public int dropRank() {
        return values().length - 1 - ordinal();
    }
}
