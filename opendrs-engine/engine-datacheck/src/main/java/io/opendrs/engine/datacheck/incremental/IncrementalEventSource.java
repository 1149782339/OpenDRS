package io.opendrs.engine.datacheck.incremental;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.TopicPartition;

/** Kafka (or test) source of pipeline CDC envelopes. Callers use assign+seek, never subscribe. */
public interface IncrementalEventSource extends AutoCloseable {

    void assignAndSeek(Map<TopicPartition, Long> nextOffsets);

    List<ConsumerRecord<String, byte[]>> poll(Duration timeout);

    /** Optional observability commit of the check's own group; resume is driven by the offset store. */
    default void commit(Map<TopicPartition, Long> nextOffsets) {
    }

    default void wakeup() {
    }

    @Override
    void close();
}
