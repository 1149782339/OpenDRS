package io.opendrs.engine.capture.transport;

/**
 * Persists the last successfully applied source-side position for a Kafka-buffered pipeline.
 * Distinct from {@code debezium_offsets} (produce progress) and from the Kafka consumer group
 * offset (read progress).
 */
public interface AppliedOffsetStore {

    void saveApplied(long pipelineId, String sourceOffsetJson, String kafkaTopic, int kafkaPartition,
            long kafkaOffset);

    /** Last saved applied row for the pipeline, or {@code null} when none. */
    AppliedOffset find(long pipelineId);

    record AppliedOffset(
            long pipelineId,
            String sourceOffsetJson,
            String kafkaTopic,
            Integer kafkaPartition,
            Long kafkaOffset) {
    }
}
