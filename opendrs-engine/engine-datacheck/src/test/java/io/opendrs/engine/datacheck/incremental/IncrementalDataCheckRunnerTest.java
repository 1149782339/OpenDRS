package io.opendrs.engine.datacheck.incremental;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.opendrs.engine.capture.transport.SourceRecordEnvelopeCodec;
import io.opendrs.engine.common.metadata.TableId;
import io.opendrs.engine.datacheck.InconsistentRowStore;
import io.opendrs.engine.jdbc.JdbcConnectionConfig;
import io.opendrs.engine.replay.applier.SourceRecordConverter;
import io.opendrs.engine.replay.event.DataChangeEvent;
import io.opendrs.engine.replay.event.Operation;
import io.opendrs.engine.replay.relation.FieldsMetaData;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.WakeupException;
import org.apache.kafka.connect.source.SourceRecord;
import org.junit.jupiter.api.Test;

class IncrementalDataCheckRunnerTest {

    private static final TopicPartition TP = new TopicPartition("opendrs.pipeline.p", 0);
    private static final TableId TABLE = TableId.of("s", "t");

    @Test
    void eventBatchDedupesKeepingLatestPk() {
        EventBatch batch = new EventBatch();
        batch.noteOffset(TP, 1);
        batch.put(TABLE, new PkRow("1", List.of(1)));
        batch.noteOffset(TP, 2);
        batch.put(TABLE, new PkRow("1", List.of(1)));
        batch.put(TABLE, new PkRow("2", List.of(2)));
        List<PkRow> rows = batch.rowsByTable().get(TABLE);
        assertEquals(2, rows.size());
        assertEquals(List.of("1", "2"), rows.stream().map(PkRow::encoded).toList());
        assertEquals(3L, batch.nextOffsets().get(TP));
    }

    @Test
    void checksBatchThenPersistsOffsetAndStops() {
        RecordingChecker checker = new RecordingChecker();
        List<Map<TopicPartition, Long>> saved = new ArrayList<>();
        AtomicBoolean stop = new AtomicBoolean(false);
        IncrementalEventSource events = new OneShotSource(List.of(
                record(10, "a"), record(11, "a"), record(12, "b")));
        ConsumerGroupOffsets offsets = new ReadyOffsets();
        IncrementalDataCheckRunner runner = runner(events, offsets, checker);

        runner.run(
                mock(JdbcConnectionConfig.class), mock(JdbcConnectionConfig.class),
                List.of(new CheckedTablePair(TABLE, TABLE)),
                Map.of(TP, 10L),
                mock(InconsistentRowStore.class),
                saved::add,
                (table, lag) -> { },
                null,
                () -> {
                    if (!saved.isEmpty()) {
                        stop.set(true);
                    }
                    return stop.get();
                });

        assertEquals(1, checker.batches.size());
        assertEquals(List.of("a", "b"), checker.batches.get(0).stream().map(PkRow::encoded).toList());
        assertEquals(13L, saved.get(0).get(TP));
    }

    @Test
    void timeoutDoesNotCheckOrCommitAndRequeues() {
        RecordingChecker checker = new RecordingChecker();
        List<Map<TopicPartition, Long>> saved = new ArrayList<>();
        AtomicInteger waits = new AtomicInteger();
        IncrementalEventSource events = new OneShotSource(List.of(record(10, "a")));
        // never advances
        ConsumerGroupOffsets offsets = new FixedOffsets(Map.of(TP, 0L));
        IncrementalDataCheckRunner runner = runner(
                events, offsets, checker,
                Duration.ofMillis(5), Duration.ofMillis(1));
        AtomicBoolean stop = new AtomicBoolean(false);
        runner.run(
                mock(JdbcConnectionConfig.class), mock(JdbcConnectionConfig.class),
                List.of(new CheckedTablePair(TABLE, TABLE)),
                Map.of(TP, 10L),
                mock(InconsistentRowStore.class),
                next -> saved.add(next),
                (table, lag) -> waits.incrementAndGet(),
                null,
                () -> {
                    if (waits.get() >= 1) {
                        stop.set(true);
                    }
                    return stop.get();
                });

        assertTrue(checker.batches.isEmpty());
        assertTrue(saved.isEmpty());
        assertTrue(waits.get() >= 1);
    }

    @Test
    void wakeupDuringPollStopsCleanlyWithoutChecking() {
        RecordingChecker checker = new RecordingChecker();
        List<Map<TopicPartition, Long>> saved = new ArrayList<>();
        IncrementalEventSource events = new IncrementalEventSource() {
            @Override
            public void assignAndSeek(Map<TopicPartition, Long> nextOffsets) {
            }

            @Override
            public List<ConsumerRecord<String, byte[]>> poll(Duration timeout) {
                throw new WakeupException();
            }

            @Override
            public void close() {
            }
        };
        IncrementalDataCheckRunner runner = runner(events, new ReadyOffsets(), checker);
        runner.run(
                mock(JdbcConnectionConfig.class), mock(JdbcConnectionConfig.class),
                List.of(new CheckedTablePair(TABLE, TABLE)),
                Map.of(TP, 10L),
                mock(InconsistentRowStore.class),
                saved::add,
                (table, lag) -> { },
                null,
                () -> false);
        assertTrue(checker.batches.isEmpty());
        assertTrue(saved.isEmpty());
    }

    private static IncrementalDataCheckRunner runner(
            IncrementalEventSource events, ConsumerGroupOffsets offsets, PkBatchChecker checker) {
        return runner(events, offsets, checker, Duration.ofMinutes(5), Duration.ofMillis(1));
    }

    private static IncrementalDataCheckRunner runner(
            IncrementalEventSource events,
            ConsumerGroupOffsets offsets,
            PkBatchChecker checker,
            Duration timeout,
            Duration poll) {
        SourceRecordEnvelopeCodec codec = mock(SourceRecordEnvelopeCodec.class);
        when(codec.deserialize(any(byte[].class))).thenAnswer(invocation -> {
            String pk = new String((byte[]) invocation.getArgument(0), java.nio.charset.StandardCharsets.UTF_8);
            return new SourceRecord(Map.of(), Map.of(), TP.topic(), 0, null, pk, null, null);
        });
        SourceRecordConverter converter = mock(SourceRecordConverter.class);
        when(converter.convertOne(any())).thenAnswer(invocation -> {
            SourceRecord record = invocation.getArgument(0);
            return Optional.of(change(String.valueOf(record.key())));
        });
        IncrementalDataCheckConfig config = new IncrementalDataCheckConfig(
                TP.topic(), "opendrs.replay.p", timeout, poll);
        return new IncrementalDataCheckRunner(
                events, offsets, config, null, codec, converter, checker);
    }

    private static DataChangeEvent change(String pk) {
        FieldsMetaData meta = new FieldsMetaData(List.of("id"), List.of(), Map.of());
        return DataChangeEvent.builder()
                .operation(Operation.UPDATE)
                .tableId(TABLE)
                .afterValues(Map.of("id", pk))
                .fieldsMetaData(meta)
                .build();
    }

    private static ConsumerRecord<String, byte[]> record(long offset, String pk) {
        return new ConsumerRecord<>(TP.topic(), TP.partition(), offset, "k", pk.getBytes());
    }

    private static final class RecordingChecker implements PkBatchChecker {
        final List<List<PkRow>> batches = new ArrayList<>();

        @Override
        public void check(CheckedTablePair tables, List<PkRow> keys, InconsistentRowStore store) {
            batches.add(List.copyOf(keys));
        }
    }

    private static final class OneShotSource implements IncrementalEventSource {
        private final List<ConsumerRecord<String, byte[]>> records;
        private boolean sent;

        OneShotSource(List<ConsumerRecord<String, byte[]>> records) {
            this.records = records;
        }

        @Override
        public void assignAndSeek(Map<TopicPartition, Long> nextOffsets) {
        }

        @Override
        public List<ConsumerRecord<String, byte[]>> poll(Duration timeout) {
            if (sent) {
                return List.of();
            }
            sent = true;
            return records;
        }

        @Override
        public void close() {
        }
    }

    private static final class ReadyOffsets extends FixedOffsets {
        ReadyOffsets() {
            super(Map.of(TP, Long.MAX_VALUE));
        }
    }

    private static class FixedOffsets implements ConsumerGroupOffsets {
        final Map<TopicPartition, Long> committed;

        FixedOffsets(Map<TopicPartition, Long> committed) {
            this.committed = new HashMap<>(committed);
        }

        @Override
        public List<TopicPartition> partitions(String topic) {
            return List.of(TP);
        }

        @Override
        public Map<TopicPartition, Long> committed(String groupId, Collection<TopicPartition> partitions) {
            return Map.copyOf(committed);
        }

        @Override
        public Map<TopicPartition, Long> beginningOffsets(Collection<TopicPartition> partitions) {
            return Map.of(TP, 0L);
        }

        @Override
        public Map<TopicPartition, Long> endOffsets(Collection<TopicPartition> partitions) {
            return Map.of(TP, 0L);
        }
    }
}
