package io.opendrs.migration.api.response;

import io.opendrs.migration.api.request.ConnectionInfo;
import io.opendrs.migration.api.request.MigrationOptions;
import io.opendrs.migration.api.request.TableSelection;
import io.opendrs.migration.domain.JobPhase;
import io.opendrs.migration.domain.JobState;
import io.opendrs.migration.domain.MigrationMode;
import java.time.Instant;

public record MigrationTaskResponse(
        Long id,
        String name,
        MigrationMode mode,
        ConnectionInfo source,
        ConnectionInfo target,
        TableSelection tables,
        MigrationOptions options,
        JobPhase jobPhase,
        JobState jobState,
        Instant createdAt,
        Instant updatedAt
) {
}
