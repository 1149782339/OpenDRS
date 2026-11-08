package io.opendrs.datacheck.domain;

/** Business phase of one check task, independent of the executor lifecycle. */
public enum DataCheckPhase {

    /** Task row exists, nothing has run yet. */
    CREATED,

    /** Full comparison is running. */
    FULL,

    /** Full comparison finished and the check is following the change stream. */
    INCREMENTAL
}
