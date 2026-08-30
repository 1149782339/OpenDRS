package io.opendrs.migration.api.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import java.util.List;

public record TableSelection(
        @NotEmpty List<@Valid SchemaObject> objects,
        @Valid TableMappings mappings
) {
}
