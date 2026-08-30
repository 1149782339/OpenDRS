package io.opendrs.migration.domain;

/**
 * Coordinator-thread lifecycle, stored separately from {@link JobPhase}.
 * {@code null} in the database means the job has never been started (create / mid-precheck).
 */
public enum JobState {
    STARTING,
    RUNNING,
    STOPPING,
    STOPPED,
    FAILED
}
