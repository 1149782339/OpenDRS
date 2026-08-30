package io.opendrs.migration.api.response;

import io.opendrs.migration.domain.DbType;
import io.opendrs.migration.domain.JobPhase;
import io.opendrs.migration.domain.JobState;
import io.opendrs.migration.domain.MigrationMode;
import java.time.Instant;

public record MigrationTaskSummary(
        Long id,
        String name,
        MigrationMode mode,
        JobPhase jobPhase,
        JobState jobState,
        SourceTargetType source,
        SourceTargetType target,
        Instant createdAt
) {
    public record SourceTargetType(DbType type) {
    }
}
