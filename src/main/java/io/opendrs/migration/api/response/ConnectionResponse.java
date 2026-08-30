package io.opendrs.migration.api.response;

import io.opendrs.migration.domain.DbType;
import java.time.Instant;
import java.util.Map;

public record ConnectionResponse(
        Long id,
        String name,
        DbType type,
        String host,
        Integer port,
        String database,
        String username,
        String password,
        Map<String, Object> extra,
        Instant createdAt,
        Instant updatedAt
) {
}
