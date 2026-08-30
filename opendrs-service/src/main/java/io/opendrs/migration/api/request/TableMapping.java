package io.opendrs.migration.api.request;

import jakarta.validation.constraints.NotBlank;

public record TableMapping(
        @NotBlank String sourceSchema,
        @NotBlank String sourceTable,
        @NotBlank String targetSchema,
        @NotBlank String targetTable
) {
}
