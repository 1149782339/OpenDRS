package io.opendrs.engine.capture.transport;

import java.util.Objects;

/**
 * Kafka + Apicurio endpoints for the {@link TransportChannel#KAFKA} path.
 *
 * <p>Serialization for this milestone is a Connect JSON envelope that preserves the Debezium
 * {@code SourceRecord} (schema + payload). That is intentionally <strong>not</strong> the
 * flat Avro shape used by {@code kafka-apicurio-cdc-demo}. {@link #schemaRegistryUrl()} is
 * reserved for a later Apicurio-backed schema strategy; the JSON codec does not require it to
 * run.
 */
public record KafkaTransportConfig(
        String bootstrapServers,
        String schemaRegistryUrl,
        String pipelinePublicId) {

    public KafkaTransportConfig {
        Objects.requireNonNull(bootstrapServers, "bootstrapServers");
        Objects.requireNonNull(pipelinePublicId, "pipelinePublicId");
        if (bootstrapServers.isBlank()) {
            throw new IllegalArgumentException("bootstrapServers must not be blank");
        }
        if (pipelinePublicId.isBlank()) {
            throw new IllegalArgumentException("pipelinePublicId must not be blank");
        }
    }

    public String topic() {
        return PipelineKafkaNames.topic(pipelinePublicId);
    }

    public String consumerGroup() {
        return PipelineKafkaNames.consumerGroup(pipelinePublicId);
    }
}
