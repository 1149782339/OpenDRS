package io.opendrs.migration.api.request;

import io.opendrs.migration.domain.DbType;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.Map;

public record ConnectionInfo(
        @NotNull DbType type,
        @NotBlank String host,
        @NotNull @Min(1) @Max(65535) Integer port,
        @NotBlank String database,
        @NotBlank String username,
        @NotBlank String password,
        Map<String, Object> extra
) {
    public ConnectionInfo masked() {
        return new ConnectionInfo(type, host, port, database, username, "***", extra);
    }
}
