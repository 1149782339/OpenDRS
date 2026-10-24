package io.opendrs.migration.api.response;

import io.opendrs.migration.api.request.ConnectionInfo;
import io.opendrs.migration.api.request.MigrationOptions;
import io.opendrs.migration.domain.MigrationPhase;
import io.opendrs.engine.core.TaskState;
import io.opendrs.migration.domain.MigrationMode;
import java.time.Instant;

public record MigrationTaskResponse(
        Long id,
        String publicId,
        String name,
        MigrationMode mode,
        ConnectionInfo source,
        ConnectionInfo target,
        MigrationOptions options,
        MigrationPhase phase,
        TaskState state,
        Instant createdAt,
        Instant updatedAt
) {
}
