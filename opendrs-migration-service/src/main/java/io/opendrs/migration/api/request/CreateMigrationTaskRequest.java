package io.opendrs.migration.api.request;

import io.opendrs.migration.domain.MigrationMode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * Task creation references the data sources the user already registered: the browser never sends
 * credentials again, and the connection list never exposes the stored password. Deleting a data
 * source that a task references is refused ({@code CONNECTION_IN_USE}).
 */
public record CreateMigrationTaskRequest(
        @NotBlank String name,
        @NotNull MigrationMode mode,
        @NotNull Long sourceConnectionId,
        @NotNull Long targetConnectionId,
        @Valid MigrationOptions options
) {
}
