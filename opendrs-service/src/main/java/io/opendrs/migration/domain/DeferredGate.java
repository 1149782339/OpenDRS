package io.opendrs.migration.domain;

/**
 * Per-pipeline progress of the deferred target objects ({@code task_pipelines.deferred_gate}).
 *
 * <ul>
 *   <li>{@code NONE}: nothing captured; the pipeline predates the feature or does not use it.</li>
 *   <li>{@code CAPTURED}: the secondary objects are recorded in {@code task_deferred_objects}. The
 *   capture never runs again — a restart only finishes the drops or the rebuilds left.</li>
 *   <li>{@code RESTORED}: every recorded object is back on the target.</li>
 * </ul>
 */
public enum DeferredGate {
    NONE,
    CAPTURED,
    RESTORED
}
