package io.opendrs.engine.datacheck.incremental;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.StringDeserializer;

/**
 * Check-owned consumer: {@code assign}+{@code seek}, group {@code opendrs.datacheck.{taskPublicId}},
 * auto-commit off. Resume position is stored in the metadata DB; group commits are observability.
 */
public final class KafkaIncrementalEventSource implements IncrementalEventSource {

    private final Consumer<String, byte[]> consumer;

    public KafkaIncrementalEventSource(String bootstrapServers, String taskPublicId) {
        this(createConsumer(bootstrapServers, taskPublicId));
    }

    KafkaIncrementalEventSource(Consumer<String, byte[]> consumer) {
        this.consumer = consumer;
    }

    @Override
    public void assignAndSeek(Map<TopicPartition, Long> nextOffsets) {
        consumer.assign(new ArrayList<>(nextOffsets.keySet()));
        for (Map.Entry<TopicPartition, Long> entry : nextOffsets.entrySet()) {
            consumer.seek(entry.getKey(), entry.getValue());
        }
    }

    @Override
    public List<ConsumerRecord<String, byte[]>> poll(Duration timeout) {
        ConsumerRecords<String, byte[]> records = consumer.poll(timeout);
        List<ConsumerRecord<String, byte[]>> list = new ArrayList<>(records.count());
        records.forEach(list::add);
        return list;
    }

    @Override
    public void commit(Map<TopicPartition, Long> nextOffsets) {
        if (nextOffsets == null || nextOffsets.isEmpty()) {
            return;
        }
        Map<TopicPartition, OffsetAndMetadata> committed = new HashMap<>();
        for (Map.Entry<TopicPartition, Long> entry : nextOffsets.entrySet()) {
            committed.put(entry.getKey(), new OffsetAndMetadata(entry.getValue()));
        }
        consumer.commitSync(committed);
    }

    @Override
    public void wakeup() {
        consumer.wakeup();
    }

    @Override
    public void close() {
        consumer.close(Duration.ofSeconds(5));
    }

    private static Consumer<String, byte[]> createConsumer(String bootstrapServers, String taskPublicId) {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "opendrs.datacheck." + taskPublicId);
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class.getName());
        props.put(ConsumerConfig.CLIENT_ID_CONFIG, "opendrs-datacheck-" + taskPublicId);
        props.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, "500");
        props.put(ConsumerConfig.ALLOW_AUTO_CREATE_TOPICS_CONFIG, "false");
        return new KafkaConsumer<>(props);
    }
}
