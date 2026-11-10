package io.opendrs.engine.datacheck.incremental;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.kafka.common.TopicPartition;

/**
 * Snapshots a replay group's committed offsets. Partitions with no committed offset start at the
 * topic beginning (earliest), so a brand-new replay group does not skip already-produced events.
 */
public final class ReplayOffsetSnapshot {

    private ReplayOffsetSnapshot() {
    }

    public static Map<TopicPartition, Long> snapshot(
            ConsumerGroupOffsets offsets, String topic, String replayGroup) {
        List<TopicPartition> partitions = offsets.partitions(topic);
        if (partitions.isEmpty()) {
            throw new IllegalStateException("Kafka topic has no partitions: " + topic);
        }
        Map<TopicPartition, Long> committed = offsets.committed(replayGroup, partitions);
        Map<TopicPartition, Long> beginning = offsets.beginningOffsets(partitions);
        Map<TopicPartition, Long> start = new LinkedHashMap<>();
        for (TopicPartition partition : partitions) {
            Long committedOffset = committed.get(partition);
            if (committedOffset != null) {
                start.put(partition, committedOffset);
            } else {
                start.put(partition, beginning.getOrDefault(partition, 0L));
            }
        }
        return start;
    }
}
