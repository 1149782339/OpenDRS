package io.opendrs.datacheck.job;

import io.opendrs.engine.datacheck.incremental.ReplayWatermark;
import io.opendrs.migration.service.MigrationLagRegistry;
import java.util.HashMap;
import java.util.Map;
import java.util.OptionalLong;
import org.apache.kafka.common.TopicPartition;

/**
 * Same-process datacheck view of migration replay watermarks held in
 * {@link MigrationLagRegistry}.
 */
final class RegistryReplayWatermark implements ReplayWatermark {

    private final MigrationLagRegistry lags;
    private final long pipelineId;
    private final String topic;

    RegistryReplayWatermark(MigrationLagRegistry lags, long pipelineId, String topic) {
        this.lags = lags;
        this.pipelineId = pipelineId;
        this.topic = topic;
    }

    @Override
    public OptionalLong lastAppliedCreateTimeMs() {
        return lags.lastAppliedCreateTimeMs(pipelineId)
                .map(OptionalLong::of)
                .orElseGet(OptionalLong::empty);
    }

    @Override
    public Map<TopicPartition, Long> committedOffsets() {
        Map<Integer, Long> next = lags.replayCommittedNext(pipelineId);
        if (next.isEmpty() || topic == null) {
            return Map.of();
        }
        Map<TopicPartition, Long> committed = new HashMap<>();
        for (Map.Entry<Integer, Long> entry : next.entrySet()) {
            committed.put(new TopicPartition(topic, entry.getKey()), entry.getValue());
        }
        return committed;
    }
}
