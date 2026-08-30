package io.opendrs.migration.api.request;

import jakarta.validation.constraints.NotBlank;
import java.util.List;

public record SchemaObject(
        @NotBlank String schema,
        List<String> tables,
        Boolean allTables,
        List<String> excludeTables
) {
}
