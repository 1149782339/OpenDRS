package io.opendrs.engine.capture.transport;

/**
 * How capture hands change events to replay.
 *
 * <ul>
 *   <li>{@link #DIRECT} — same process: apply to the target, then mark {@code debezium_offsets}
 *       (today's semantics; the default).</li>
 *   <li>{@link #KAFKA} — produce to a per-pipeline topic first, then mark {@code debezium_offsets};
 *       a separate consumer applies and writes {@code pipeline_applied_offsets}.</li>
 * </ul>
 */
public enum TransportChannel {
    DIRECT,
    KAFKA;

    public static TransportChannel fromConfig(String raw) {
        if (raw == null || raw.isBlank()) {
            return DIRECT;
        }
        return TransportChannel.valueOf(raw.trim().toUpperCase());
    }
}
