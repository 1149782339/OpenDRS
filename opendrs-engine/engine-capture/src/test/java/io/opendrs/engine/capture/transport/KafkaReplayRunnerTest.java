package io.opendrs.engine.capture.transport;

import static org.assertj.core.api.Assertions.assertThat;

import io.opendrs.engine.capture.DebeziumCdcEngineFactory;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.kafka.clients.consumer.MockConsumer;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.consumer.OffsetResetStrategy;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.Test;

class KafkaReplayRunnerTest {

    private static final String TOPIC = "opendrs.pipeline.test-pid";
    private static final TopicPartition TP = new TopicPartition(TOPIC, 0);

    @Test
    void commitIntervalIsThreeSeconds() {
        assertThat(KafkaReplayRunner.COMMIT_INTERVAL).isEqualTo(Duration.ofSeconds(3));
    }

    @Test
    void kafkaCaptureFlushIntervalConstantIs3000Ms() {
        assertThat(DebeziumCdcEngineFactory.KAFKA_OFFSET_FLUSH_INTERVAL_MS).isEqualTo("3000");
    }

    @Test
    void doesNotFlushBeforeInterval_thenCommitAsyncAfterThreeSeconds() {
        AtomicLong clock = new AtomicLong(0L);
        RecordingStore store = new RecordingStore();
        MockConsumer<String, byte[]> consumer = new MockConsumer<>(OffsetResetStrategy.EARLIEST);
        consumer.assign(List.of(TP));
        consumer.updateBeginningOffsets(Map.of(TP, 0L));

        KafkaReplayRunner runner = newRunner(store, consumer, clock);
        runner.notePendingForTest(TOPIC, 0, 5L, "{\"pos\":5}");

        clock.set(Duration.ofSeconds(2).toNanos());
        runner.flushIfDue(false);
        assertThat(store.saves).isEmpty();
        assertThat(consumer.committed(Set.of(TP))).doesNotContainKey(TP);

        clock.set(Duration.ofSeconds(3).toNanos());
        runner.flushIfDue(false);
        assertThat(store.saves).hasSize(1);
        assertThat(store.saves.get(0).pipelineId()).isEqualTo(42L);
        assertThat(store.saves.get(0).kafkaOffset()).isEqualTo(5L);
        assertThat(store.saves.get(0).sourceOffsetJson()).isEqualTo("{\"pos\":5}");
        Map<TopicPartition, OffsetAndMetadata> committed = consumer.committed(Set.of(TP));
        assertThat(committed.get(TP).offset()).isEqualTo(6L);
    }

    @Test
    void forceFlushCommitsImmediatelyEvenInsideInterval() {
        AtomicLong clock = new AtomicLong(0L);
        RecordingStore store = new RecordingStore();
        MockConsumer<String, byte[]> consumer = new MockConsumer<>(OffsetResetStrategy.EARLIEST);
        consumer.assign(List.of(TP));
        consumer.updateBeginningOffsets(Map.of(TP, 0L));

        KafkaReplayRunner runner = newRunner(store, consumer, clock);
        runner.notePendingForTest(TOPIC, 0, 20L, "{\"pos\":20}");

        clock.set(Duration.ofMillis(50).toNanos());
        runner.flushIfDue(true);

        assertThat(store.saves).hasSize(1);
        assertThat(store.saves.get(0).kafkaOffset()).isEqualTo(20L);
        assertThat(consumer.committed(Set.of(TP)).get(TP).offset()).isEqualTo(21L);
    }

    @Test
    void secondFlushIsNoopUntilNewPending() {
        AtomicLong clock = new AtomicLong(0L);
        RecordingStore store = new RecordingStore();
        MockConsumer<String, byte[]> consumer = new MockConsumer<>(OffsetResetStrategy.EARLIEST);
        consumer.assign(List.of(TP));
        consumer.updateBeginningOffsets(Map.of(TP, 0L));

        KafkaReplayRunner runner = newRunner(store, consumer, clock);
        runner.notePendingForTest(TOPIC, 0, 1L, "{}");
        clock.set(Duration.ofSeconds(3).toNanos());
        runner.flushIfDue(false);
        runner.flushIfDue(true);
        assertThat(store.saves).hasSize(1);
    }

    private static KafkaReplayRunner newRunner(
            AppliedOffsetStore store,
            MockConsumer<String, byte[]> consumer,
            AtomicLong clock) {
        return new KafkaReplayRunner(
                42L,
                new KafkaTransportConfig("localhost:9092", null, "test-pid"),
                batch -> {
                },
                () -> {
                },
                store,
                new SourceRecordEnvelopeCodec(),
                consumer,
                clock::get);
    }

    private static final class RecordingStore implements AppliedOffsetStore {
        final List<AppliedOffset> saves = new ArrayList<>();

        @Override
        public void saveApplied(
                long pipelineId,
                String sourceOffsetJson,
                String kafkaTopic,
                int kafkaPartition,
                long kafkaOffset) {
            saves.add(new AppliedOffset(
                    pipelineId, sourceOffsetJson, kafkaTopic, kafkaPartition, kafkaOffset));
        }

        @Override
        public AppliedOffset find(long pipelineId) {
            return saves.isEmpty() ? null : saves.get(saves.size() - 1);
        }
    }
}
