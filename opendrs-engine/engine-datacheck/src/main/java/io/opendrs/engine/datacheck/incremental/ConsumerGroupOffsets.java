package io.opendrs.engine.datacheck.incremental;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import org.apache.kafka.common.TopicPartition;

/** Read-only view of a Kafka topic's partitions and a consumer group's committed offsets. */
public interface ConsumerGroupOffsets extends AutoCloseable {

    List<TopicPartition> partitions(String topic);

    /**
     * Committed offset (next record to read) per partition. Partitions with no commit are omitted.
     */
    Map<TopicPartition, Long> committed(String groupId, Collection<TopicPartition> partitions);

    Map<TopicPartition, Long> beginningOffsets(Collection<TopicPartition> partitions);

    Map<TopicPartition, Long> endOffsets(Collection<TopicPartition> partitions);

    @Override
    default void close() {
    }
}
