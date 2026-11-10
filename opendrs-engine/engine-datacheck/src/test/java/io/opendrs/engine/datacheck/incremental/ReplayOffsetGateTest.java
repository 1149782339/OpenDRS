package io.opendrs.engine.datacheck.incremental;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.Test;

class ReplayOffsetGateTest {

    private static final TopicPartition TP = new TopicPartition("t", 0);

    @Test
    void readyWhenCommittedPassesEventOffset() {
        FakeOffsets offsets = new FakeOffsets(Map.of(TP, 11L));
        ReplayOffsetGate gate = new ReplayOffsetGate(
                offsets, "opendrs.replay.x", Duration.ofMinutes(5), Duration.ofMillis(1),
                () -> 0L, duration -> { });
        ReplayOffsetGate.Result result = gate.await(Map.of(TP, 10L), () -> false);
        assertTrue(result.ready());
    }

    @Test
    void timeoutRequeuesWithoutReady() {
        FakeOffsets offsets = new FakeOffsets(Map.of(TP, 5L));
        AtomicLong clock = new AtomicLong(0);
        ReplayOffsetGate gate = new ReplayOffsetGate(
                offsets, "g", Duration.ofMillis(20), Duration.ofMillis(5),
                clock::get, duration -> clock.addAndGet(duration.toNanos()));
        ReplayOffsetGate.Result result = gate.await(Map.of(TP, 10L), () -> false);
        assertFalse(result.ready());
        assertTrue(result.lagMs() >= 20);
    }

    @Test
    void becomesReadyAfterReplayAdvances() {
        FakeOffsets offsets = new FakeOffsets(Map.of(TP, 5L));
        AtomicInteger polls = new AtomicInteger();
        AtomicLong clock = new AtomicLong(0);
        ReplayOffsetGate gate = new ReplayOffsetGate(
                offsets, "g", Duration.ofSeconds(5), Duration.ofMillis(1),
                clock::get,
                duration -> {
                    if (polls.incrementAndGet() >= 2) {
                        offsets.committed.put(TP, 11L);
                    }
                });
        ReplayOffsetGate.Result result = gate.await(Map.of(TP, 10L), () -> false);
        assertTrue(result.ready());
    }

    @Test
    void passedRequiresStrictlyGreaterThanEventOffset() {
        assertFalse(ReplayOffsetGate.passed(Map.of(TP, 10L), Map.of(TP, 10L)));
        assertTrue(ReplayOffsetGate.passed(Map.of(TP, 11L), Map.of(TP, 10L)));
    }

    private static final class FakeOffsets implements ConsumerGroupOffsets {
        final Map<TopicPartition, Long> committed;

        FakeOffsets(Map<TopicPartition, Long> committed) {
            this.committed = new HashMap<>(committed);
        }

        @Override
        public List<TopicPartition> partitions(String topic) {
            return List.copyOf(committed.keySet());
        }

        @Override
        public Map<TopicPartition, Long> committed(String groupId, Collection<TopicPartition> partitions) {
            return Map.copyOf(committed);
        }

        @Override
        public Map<TopicPartition, Long> beginningOffsets(Collection<TopicPartition> partitions) {
            return Map.of();
        }

        @Override
        public Map<TopicPartition, Long> endOffsets(Collection<TopicPartition> partitions) {
            return Map.of();
        }
    }
}
