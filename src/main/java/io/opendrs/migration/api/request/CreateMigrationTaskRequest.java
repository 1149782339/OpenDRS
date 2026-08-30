package io.opendrs.migration.api.request;

import io.opendrs.migration.domain.MigrationMode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record CreateMigrationTaskRequest(
        @NotBlank String name,
        @NotNull MigrationMode mode,
        @NotNull @Valid ConnectionInfo source,
        @NotNull @Valid ConnectionInfo target,
        @NotNull @Valid TableSelection tables,
        @Valid MigrationOptions options
) {
}
