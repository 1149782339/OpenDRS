package io.opendrs.datacheck.domain;

/** Per-table business phase, independent of the executor lifecycle. */
public enum DataCheckPhase {

    /** The table has not started yet. */
    PENDING,

    /** Full comparison is running. */
    FULL,

    /** Full comparison finished and the table is following the change stream. */
    INCREMENTAL
}
