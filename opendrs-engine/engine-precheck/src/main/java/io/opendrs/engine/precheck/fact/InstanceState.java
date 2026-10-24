package io.opendrs.engine.precheck.fact;

/**
 * Whether the endpoint can be written to. A standby (PostgreSQL {@code pg_is_in_recovery()}, MySQL
 * {@code read_only}) can be read but never receives the replicated rows.
 */
public record InstanceState(boolean writable, String detail) {
}
