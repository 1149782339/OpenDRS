package io.opendrs.engine.datacheck.incremental;

import java.util.Map;
import java.util.OptionalLong;
import org.apache.kafka.common.TopicPartition;

/**
 * Same-process view of migration replay progress, used to compute datacheck lag continuously
 * (including while waiting for the target). Implementations typically read
 * {@code MigrationLagRegistry}. When capture/replay run elsewhere, leave this empty and the
 * runner falls back to the consumer-group committed offsets (time lag only if this process
 * has seen record timestamps).
 */
public interface ReplayWatermark {

    ReplayWatermark NONE = new ReplayWatermark() { };

    /** Last applied Kafka record CreateTime across partitions. */
    default OptionalLong lastAppliedCreateTimeMs() {
        return OptionalLong.empty();
    }

    /**
     * Replay committed next-to-read offsets (last applied + 1) keyed by topic-partition.
     * Empty if this process does not have in-memory apply watermarks.
     */
    default Map<TopicPartition, Long> committedOffsets() {
        return Map.of();
    }
}
