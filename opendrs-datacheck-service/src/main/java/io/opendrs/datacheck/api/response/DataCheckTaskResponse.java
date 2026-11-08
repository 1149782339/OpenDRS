package io.opendrs.datacheck.api.response;

import io.opendrs.datacheck.api.request.DataCheckOptions;
import io.opendrs.datacheck.domain.DataCheckMode;
import io.opendrs.engine.core.TaskState;
import java.time.Instant;
import java.util.List;

public record DataCheckTaskResponse(
        Long id,
        String publicId,
        Long migrationTaskId,
        Long pipelineId,
        String name,
        DataCheckMode mode,
        TaskState state,
        List<CheckedTable> tables,
        DataCheckOptions options,
        Long lagMs,
        String errorMessage,
        Instant createdAt,
        Instant updatedAt) {
}
