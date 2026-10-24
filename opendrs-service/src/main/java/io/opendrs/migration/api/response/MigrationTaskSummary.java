package io.opendrs.migration.api.response;

import io.opendrs.migration.domain.DbType;
import io.opendrs.migration.domain.MigrationPhase;
import io.opendrs.engine.core.TaskState;
import io.opendrs.migration.domain.MigrationMode;
import java.time.Instant;

/**
 * One row of the task list.
 *
 * @param lagMs how far the target trails the source right now, in milliseconds; {@code null} unless
 *     the task is {@code RUNNING} in {@code INCREMENTAL}, because a snapshot, a stopped run and a
 *     prechecked-but-not-started task all have nothing to be behind on
 */
public record MigrationTaskSummary(
        Long id,
        String publicId,
        String name,
        MigrationMode mode,
        MigrationPhase phase,
        TaskState state,
        SourceTargetType source,
        SourceTargetType target,
        Instant createdAt,
        Long lagMs
) {
    public record SourceTargetType(DbType type) {
    }
}
