package io.opendrs.migration.api.request;

import jakarta.validation.constraints.NotBlank;

/**
 * One table in a REST selection: {@code schema.table}, two parts only. The catalog component of
 * {@code io.opendrs.common.metadata.TableId} is a JDBC artefact (MySQL reports the database as the
 * catalog) and is never part of the wire format.
 */
public record SelectedTable(@NotBlank String schema, @NotBlank String table) {
}
