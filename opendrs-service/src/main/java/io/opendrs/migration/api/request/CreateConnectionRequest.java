package io.opendrs.migration.api.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record CreateConnectionRequest(
        @NotBlank String name,
        @NotNull @Valid ConnectionInfo connection
) {
}
