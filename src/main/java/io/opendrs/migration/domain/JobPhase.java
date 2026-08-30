package io.opendrs.migration.domain;

/**
 * Pipeline location. Independent of {@link JobState} (thread lifecycle).
 *
 * <p>{@code CREATED → PRECHECKING → PRECHECKED → SCHEMA_SNAPSHOT → FULL → INCREMENTAL}.
 */
public enum JobPhase {
    CREATED,
    PRECHECKING,
    PRECHECKED,
    SCHEMA_SNAPSHOT,
    FULL,
    INCREMENTAL
}
