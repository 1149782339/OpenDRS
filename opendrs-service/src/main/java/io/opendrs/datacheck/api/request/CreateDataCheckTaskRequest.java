package io.opendrs.datacheck.api.request;

import io.opendrs.datacheck.domain.DataCheckMode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * Creates one check under an existing migration task. The covered tables are always an explicit
 * source-side list; target tables come from the migration task's mapping.
 */
public record CreateDataCheckTaskRequest(
        @NotNull Long migrationTaskId,
        /** Physical direction of a bidirectional task; null means the forward pipeline. */
        Long pipelineId,
        @NotBlank @Size(max = 128) String name,
        @NotNull DataCheckMode mode,
        @NotEmpty @Valid List<SelectedTable> tables,
        @Valid DataCheckOptions options) {
}
