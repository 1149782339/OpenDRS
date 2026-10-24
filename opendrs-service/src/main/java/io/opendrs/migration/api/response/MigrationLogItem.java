package io.opendrs.migration.api.response;

import io.opendrs.common.log.MigrationLogLevel;
import java.time.Instant;

public record MigrationLogItem(Long id, Instant loggedAt, MigrationLogLevel level, String message) {
}
