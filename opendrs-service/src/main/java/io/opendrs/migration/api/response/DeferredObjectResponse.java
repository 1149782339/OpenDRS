package io.opendrs.migration.api.response;

import io.opendrs.migration.domain.DeferredObjectRecord;
import java.time.Instant;

/**
 * One secondary object of a target table that the task dropped for the full load: its state on
 * the way back (DROPPED → REBUILT, or FAILED with the database's message).
 */
public record DeferredObjectResponse(
        Long pipelineId,
        String schema,
        String table,
        String objectType,
        String objectName,
        String state,
        int attempts,
        String lastError,
        String createDdl,
        Instant updatedAt) {

    public static DeferredObjectResponse of(DeferredObjectRecord row) {
        return new DeferredObjectResponse(
                row.getPipelineId(),
                row.getSchemaName(),
                row.getTableName(),
                row.getObjectType() == null ? null : row.getObjectType().name(),
                row.getObjectName(),
                row.getState() == null ? null : row.getState().name(),
                row.getAttempts(),
                row.getLastError(),
                row.getCreateDdl(),
                row.getUpdatedAt());
    }
}
