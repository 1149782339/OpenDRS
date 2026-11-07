package io.opendrs.engine.capture.transport;

import java.util.Objects;

/**
 * Derives Kafka topic / consumer-group names from {@code task_pipelines.public_id}.
 * Names are not persisted — renaming would drift; the public_id is the sole binding key.
 */
public final class PipelineKafkaNames {

    public static final String TOPIC_PREFIX = "opendrs.pipeline.";
    public static final String GROUP_PREFIX = "opendrs.replay.";

    private PipelineKafkaNames() {
    }

    public static String topic(String pipelinePublicId) {
        return TOPIC_PREFIX + requirePublicId(pipelinePublicId);
    }

    public static String consumerGroup(String pipelinePublicId) {
        return GROUP_PREFIX + requirePublicId(pipelinePublicId);
    }

    private static String requirePublicId(String pipelinePublicId) {
        Objects.requireNonNull(pipelinePublicId, "pipelinePublicId");
        if (pipelinePublicId.isBlank()) {
            throw new IllegalArgumentException("pipelinePublicId must not be blank");
        }
        return pipelinePublicId;
    }
}
