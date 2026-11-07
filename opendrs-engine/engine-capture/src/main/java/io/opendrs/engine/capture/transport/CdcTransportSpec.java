package io.opendrs.engine.capture.transport;

/**
 * How incremental CDC change events move from capture to replay.
 *
 * <ul>
 *   <li>{@link #DIRECT} — same process: apply to the target, then mark {@code debezium_offsets}
 *       (today's semantics; the default).</li>
 *   <li>{@link #KAFKA} — produce streaming/incremental events to a per-pipeline topic first, then
 *       mark {@code debezium_offsets}; a separate consumer applies and writes
 *       {@code pipeline_applied_offsets}. Full-load / snapshot rows always bypass Kafka (DIRECT).</li>
 * </ul>
 */
public enum CdcTransportSpec {
    DIRECT,
    KAFKA;

    public static CdcTransportSpec fromConfig(String raw) {
        if (raw == null || raw.isBlank()) {
            return DIRECT;
        }
        return CdcTransportSpec.valueOf(raw.trim().toUpperCase());
    }
}
