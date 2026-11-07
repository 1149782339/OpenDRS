package io.opendrs.engine.capture.transport;

/**
 * Per-pipeline transport selection carried on {@code EngineSpec} / {@code DataMigrationParam}.
 * Default is {@link TransportChannel#DIRECT} so existing callers stay on today's apply→mark path.
 */
public record TransportSpec(TransportChannel channel, KafkaTransportConfig kafka) {

    public TransportSpec {
        channel = channel == null ? TransportChannel.DIRECT : channel;
        if (channel == TransportChannel.KAFKA && kafka == null) {
            throw new IllegalArgumentException("KAFKA transport requires KafkaTransportConfig");
        }
    }

    public static TransportSpec direct() {
        return new TransportSpec(TransportChannel.DIRECT, null);
    }

    public static TransportSpec kafka(KafkaTransportConfig config) {
        return new TransportSpec(TransportChannel.KAFKA, config);
    }

    public boolean isKafka() {
        return channel == TransportChannel.KAFKA;
    }
}
