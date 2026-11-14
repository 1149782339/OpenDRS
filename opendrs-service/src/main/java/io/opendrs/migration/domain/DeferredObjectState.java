package io.opendrs.migration.domain;

/** Lifecycle of one row in {@code task_deferred_objects}. */
public enum DeferredObjectState {
    /** Recorded from the target catalog, still present on the target. */
    CAPTURED,
    /** Dropped from the target before the full load. */
    DROPPED,
    /** Created again on the target. */
    REBUILT,
    /** The last rebuild attempt failed; {@code last_error} says why. */
    FAILED
}
