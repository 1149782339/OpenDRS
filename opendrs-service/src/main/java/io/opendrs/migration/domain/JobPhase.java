package io.opendrs.migration.domain;

/**
 * Pipeline location. Independent of {@link JobState} (thread lifecycle).
 *
 * <p>{@code CREATED → PRECHECKING → PRECHECKED → SCHEMA_SNAPSHOT → INCREMENTAL}. {@code FULL}
 * remains on the enum for stored rows; the coordinator never runs a FULL round and will CAS
 * {@code FULL → INCREMENTAL} if it sees that phase.
 */
public enum JobPhase {
    CREATED,
    PRECHECKING,
    PRECHECKED,
    SCHEMA_SNAPSHOT,
    FULL,
    INCREMENTAL
}
