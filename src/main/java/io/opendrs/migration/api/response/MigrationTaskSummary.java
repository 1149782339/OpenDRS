package io.opendrs.migration.api.response;

import io.opendrs.migration.domain.DbType;
import io.opendrs.migration.domain.MigrationMode;
import io.opendrs.migration.domain.TaskState;
import java.time.Instant;

public record MigrationTaskSummary(
        Long id,
        String name,
        MigrationMode mode,
        TaskState state,
        SourceTargetType source,
        SourceTargetType target,
        Instant createdAt
) {
    public record SourceTargetType(DbType type) {
    }
}
