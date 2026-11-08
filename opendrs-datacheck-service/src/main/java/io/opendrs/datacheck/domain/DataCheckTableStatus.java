package io.opendrs.datacheck.domain;

/** Execution status of one table's current phase. */
public enum DataCheckTableStatus {

    PENDING,
    RUNNING,
    COMPLETED,
    FAILED,
    STOPPED
}
