package io.opendrs.engine.datacheck.incremental;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ExecutionException;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.ListConsumerGroupOffsetsResult;
import org.apache.kafka.clients.admin.OffsetSpec;
import org.apache.kafka.clients.admin.TopicDescription;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.TopicPartitionInfo;

/** AdminClient-backed offset lookups. */
public final class KafkaConsumerGroupOffsets implements ConsumerGroupOffsets {

    private final AdminClient admin;

    public KafkaConsumerGroupOffsets(String bootstrapServers) {
        Properties props = new Properties();
        props.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(AdminClientConfig.CLIENT_ID_CONFIG, "opendrs-datacheck-offsets");
        props.put(AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG, "10000");
        this.admin = AdminClient.create(props);
    }

    KafkaConsumerGroupOffsets(AdminClient admin) {
        this.admin = admin;
    }

    @Override
    public List<TopicPartition> partitions(String topic) {
        try {
            Map<String, TopicDescription> descriptions =
                    admin.describeTopics(List.of(topic)).allTopicNames().get();
            TopicDescription description = descriptions.get(topic);
            if (description == null) {
                return List.of();
            }
            List<TopicPartition> partitions = new ArrayList<>();
            for (TopicPartitionInfo info : description.partitions()) {
                partitions.add(new TopicPartition(topic, info.partition()));
            }
            return partitions;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted describing topic " + topic, ex);
        } catch (ExecutionException ex) {
            throw new IllegalStateException("failed to describe topic " + topic, ex.getCause());
        }
    }

    @Override
    public Map<TopicPartition, Long> committed(String groupId, Collection<TopicPartition> partitions) {
        try {
            ListConsumerGroupOffsetsResult result = admin.listConsumerGroupOffsets(groupId);
            Map<TopicPartition, OffsetAndMetadata> all = result.partitionsToOffsetAndMetadata().get();
            Map<TopicPartition, Long> committed = new HashMap<>();
            for (TopicPartition partition : partitions) {
                OffsetAndMetadata meta = all.get(partition);
                if (meta != null) {
                    committed.put(partition, meta.offset());
                }
            }
            return committed;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted reading offsets of group " + groupId, ex);
        } catch (ExecutionException ex) {
            throw new IllegalStateException("failed to read offsets of group " + groupId, ex.getCause());
        }
    }

    @Override
    public Map<TopicPartition, Long> beginningOffsets(Collection<TopicPartition> partitions) {
        return listOffsets(partitions, OffsetSpec.earliest());
    }

    @Override
    public Map<TopicPartition, Long> endOffsets(Collection<TopicPartition> partitions) {
        return listOffsets(partitions, OffsetSpec.latest());
    }

    private Map<TopicPartition, Long> listOffsets(
            Collection<TopicPartition> partitions, OffsetSpec spec) {
        if (partitions.isEmpty()) {
            return Map.of();
        }
        Map<TopicPartition, OffsetSpec> request = new HashMap<>();
        for (TopicPartition partition : partitions) {
            request.put(partition, spec);
        }
        try {
            Map<TopicPartition, Long> result = new HashMap<>();
            admin.listOffsets(request).all().get().forEach(
                    (partition, info) -> result.put(partition, info.offset()));
            return result;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted listing offsets", ex);
        } catch (ExecutionException ex) {
            throw new IllegalStateException("failed to list offsets", ex.getCause());
        }
    }

    @Override
    public void close() {
        admin.close(Duration.ofSeconds(5));
    }
}
