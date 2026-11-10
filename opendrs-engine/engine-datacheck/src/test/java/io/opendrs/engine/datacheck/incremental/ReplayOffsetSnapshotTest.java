package io.opendrs.engine.datacheck.incremental;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.Test;

class ReplayOffsetSnapshotTest {

    @Test
    void usesCommittedOffsetWhenPresentOtherwiseBeginning() {
        TopicPartition first = new TopicPartition("t", 0);
        TopicPartition second = new TopicPartition("t", 1);
        ConsumerGroupOffsets offsets = new ConsumerGroupOffsets() {
            @Override
            public List<TopicPartition> partitions(String topic) {
                return List.of(first, second);
            }

            @Override
            public Map<TopicPartition, Long> committed(String groupId, Collection<TopicPartition> partitions) {
                return Map.of(first, 15L);
            }

            @Override
            public Map<TopicPartition, Long> beginningOffsets(Collection<TopicPartition> partitions) {
                return Map.of(first, 0L, second, 3L);
            }

            @Override
            public Map<TopicPartition, Long> endOffsets(Collection<TopicPartition> partitions) {
                return Map.of();
            }
        };
        Map<TopicPartition, Long> start = ReplayOffsetSnapshot.snapshot(offsets, "t", "opendrs.replay.x");
        assertEquals(15L, start.get(first));
        assertEquals(3L, start.get(second));
    }
}
