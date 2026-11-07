package io.opendrs.engine.capture.transport;

/**
 * Resolved per-pipeline transport: which {@link CdcTransportSpec} plus Kafka endpoints when needed.
 * Default is {@link CdcTransportSpec#DIRECT} so existing callers stay on today's apply→mark path.
 */
public record ResolvedCdcTransport(CdcTransportSpec channel, KafkaTransportConfig kafka) {

    public ResolvedCdcTransport {
        channel = channel == null ? CdcTransportSpec.DIRECT : channel;
        if (channel == CdcTransportSpec.KAFKA && kafka == null) {
            throw new IllegalArgumentException("KAFKA transport requires KafkaTransportConfig");
        }
    }

    public static ResolvedCdcTransport direct() {
        return new ResolvedCdcTransport(CdcTransportSpec.DIRECT, null);
    }

    public static ResolvedCdcTransport kafka(KafkaTransportConfig config) {
        return new ResolvedCdcTransport(CdcTransportSpec.KAFKA, config);
    }

    public boolean isKafka() {
        return channel == CdcTransportSpec.KAFKA;
    }
}
