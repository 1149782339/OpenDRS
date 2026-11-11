package io.opendrs.migration.api.response;

import io.opendrs.engine.capture.transport.CdcTransportSpec;
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
        /** Endpoint ids; the UI needs them to enumerate schemas/tables for a data check. */
        Long sourceConnectionId,
        Long targetConnectionId,
        MigrationOptions options,
        /**
         * Effective incremental channel: the task's {@code options.cdcTransportSpec} when set,
         * otherwise the process-wide {@code opendrs.transport.channel}. Data checks need it to
         * reject incremental modes on DIRECT before the task is created.
         */
        CdcTransportSpec transportChannel,
        MigrationPhase phase,
        TaskState state,
        Instant createdAt,
        Instant updatedAt
) {
}
