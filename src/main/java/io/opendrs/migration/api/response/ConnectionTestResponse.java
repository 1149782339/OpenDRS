package io.opendrs.migration.api.response;

public record ConnectionTestResponse(boolean ok, Long latencyMs) {
}
