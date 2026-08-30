package io.opendrs.migration.api.response;

import io.opendrs.migration.api.request.ConnectionInfo;
import io.opendrs.migration.api.request.MigrationOptions;
import io.opendrs.migration.api.request.TableSelection;
import io.opendrs.migration.domain.MigrationMode;
import io.opendrs.migration.domain.TaskState;
import java.time.Instant;

public record MigrationTaskResponse(
        Long id,
        String name,
        MigrationMode mode,
        ConnectionInfo source,
        ConnectionInfo target,
        TableSelection tables,
        MigrationOptions options,
        TaskState state,
        Instant createdAt,
        Instant updatedAt
) {
}
