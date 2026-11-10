package io.opendrs.engine.capture;

import static org.assertj.core.api.Assertions.assertThat;

import io.opendrs.common.progress.FullLoadCheckpointListener;
import io.opendrs.common.progress.MigrationLagListener;
import io.opendrs.engine.common.log.MigrationLogListener;
import io.opendrs.engine.replay.ReplayConfig;
import io.opendrs.engine.replay.applier.Applier;
import io.opendrs.engine.replay.context.TaskContext;
import io.opendrs.engine.replay.metrics.ReplayDirection;
import io.opendrs.engine.replay.metrics.ReplayMetrics;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.kafka.connect.data.Schema;
import org.apache.kafka.connect.data.SchemaBuilder;
import org.apache.kafka.connect.data.Struct;
import org.apache.kafka.connect.source.SourceRecord;
import org.junit.jupiter.api.Test;

class BatchApplyHandlerCaptureCountTest {

    @Test
    void kafkaReplayHandlerDoesNotIncrementCaptureCounter() throws Exception {
        CountingMetrics metrics = new CountingMetrics();
        try (BatchApplyHandler handler = handler(metrics, false, false)) {
            handler.applyBatch(List.of(record(), record()));
        }
        assertThat(metrics.captured.get()).isZero();
    }

    @Test
    void directOrSnapshotHandlerCountsOnce() throws Exception {
        CountingMetrics metrics = new CountingMetrics();
        try (BatchApplyHandler handler = handler(metrics, true, true)) {
            handler.applyBatch(List.of(record(), record(), record()));
        }
        assertThat(metrics.captured.get()).isEqualTo(3);
    }

    private static BatchApplyHandler handler(
            ReplayMetrics metrics, boolean reportCapture, boolean countCaptured) {
        ReplayConfig config = ReplayConfig.builder()
                .connectionProvider(() -> {
                    throw new UnsupportedOperationException("no target in unit test");
                })
                .metrics(metrics)
                .build();
        Applier<Collection<SourceRecord>> applier = new Applier<>() {
            @Override
            public void prepare(TaskContext taskContext) {
            }

            @Override
            public void apply(Collection<SourceRecord> records) {
            }

            @Override
            public void release() {
            }
        };
        return new BatchApplyHandler(
                1L, 1L, config, MigrationLogListener.NONE, MigrationLagListener.NONE,
                FullLoadCheckpointListener.NONE, applier, 0L, reportCapture, countCaptured);
    }

    private static SourceRecord record() {
        Schema schema = SchemaBuilder.struct().field("op", Schema.STRING_SCHEMA).build();
        return new SourceRecord(
                Map.of("server", "s"), Map.of("file", "mysql-bin.000001", "pos", 10L),
                "t", null, null, schema, new Struct(schema).put("op", "u"));
    }

    private static final class CountingMetrics implements ReplayMetrics {
        private final AtomicInteger captured = new AtomicInteger();

        @Override
        public void captured(ReplayDirection direction, long taskId, int events) {
            captured.addAndGet(events);
        }
    }
}
