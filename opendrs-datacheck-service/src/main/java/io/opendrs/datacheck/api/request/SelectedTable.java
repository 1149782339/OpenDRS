package io.opendrs.datacheck.api.request;

import jakarta.validation.constraints.NotBlank;

/** One explicit source table the check covers. */
public record SelectedTable(@NotBlank String schema, @NotBlank String table) {
}
