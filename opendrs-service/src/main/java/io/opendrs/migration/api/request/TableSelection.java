package io.opendrs.migration.api.request;

import jakarta.validation.Valid;
import java.util.List;

/**
 * What a task migrates, as the client states it.
 *
 * <p>All four lists empty means <em>everything</em> on the source: every schema the connection can
 * read, and every schema or table created after the task starts. Otherwise the scope is either
 * {@code schemas} (whole schemas) or {@code tables} (individual tables), never both — the
 * combination cannot be expressed by one Debezium connector, so it is rejected instead of being
 * silently widened.
 *
 * <p>"Schema" is the neutral word for a MySQL database and a PostgreSQL schema; the UI words it per
 * database type.
 */
public record TableSelection(
        List<String> schemas,
        List<@Valid SelectedTable> tables,
        List<String> excludeSchemas,
        List<@Valid SelectedTable> excludeTables,
        @Valid TableMappings mappings
) {
}
