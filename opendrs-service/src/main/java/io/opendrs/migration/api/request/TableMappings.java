package io.opendrs.migration.api.request;

import jakarta.validation.Valid;
import java.util.List;

public record TableMappings(
        List<@Valid SchemaMapping> schema,
        List<@Valid TableMapping> tables
) {
}
