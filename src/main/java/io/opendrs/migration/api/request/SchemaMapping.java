package io.opendrs.migration.api.request;

import jakarta.validation.constraints.NotBlank;

public record SchemaMapping(
        @NotBlank String source,
        @NotBlank String target
) {
}
