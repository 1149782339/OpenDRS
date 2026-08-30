package io.opendrs.debezium;

import io.debezium.engine.DebeziumEngine;
import io.debezium.engine.RecordChangeEvent;
import io.opendrs.sink.SinkConfig;
import io.opendrs.sink.applier.ChangeEventApplier;
import io.opendrs.sink.applier.JdbcApplier;
import io.opendrs.sink.context.TaskContext;
import io.opendrs.sink.exception.ApplierException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.kafka.connect.source.SourceRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Production change consumer: converts Debezium {@link SourceRecord}s and applies them through
 * {@code opendrs-sink} onto the task target.
 */
public final class SinkApplyChangeConsumer
        implements DebeziumEngine.ChangeConsumer<RecordChangeEvent<SourceRecord>>, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(SinkApplyChangeConsumer.class);

    private final long taskId;
    private final JdbcApplier jdbcApplier;
    private final ChangeEventApplier applier;
    private final AtomicBoolean running = new AtomicBoolean(true);

    public SinkApplyChangeConsumer(long taskId, SinkConfig config) {
        this.taskId = taskId;
        this.jdbcApplier = new JdbcApplier(running::get, config);
        this.applier = new ChangeEventApplier(jdbcApplier, config);
        this.applier.prepare(TaskContext.empty());
    }

    JdbcApplier jdbcApplier() {
        return jdbcApplier;
    }

    @Override
    public void handleBatch(
            List<RecordChangeEvent<SourceRecord>> records,
            DebeziumEngine.RecordCommitter<RecordChangeEvent<SourceRecord>> committer)
            throws InterruptedException {
        List<SourceRecord> batch = new ArrayList<>(records.size());
        for (RecordChangeEvent<SourceRecord> event : records) {
            SourceRecord record = event.record();
            batch.add(record);
            log.info(
                    "CDC record task={} topic={} key={} sourceOffset={} op={}",
                    taskId,
                    record.topic(),
                    record.key(),
                    record.sourceOffset(),
                    LoggingChangeConsumer.extractOp(record));
        }
        try {
            applier.apply(batch);
        } catch (ApplierException ex) {
            throw new IllegalStateException("Failed to apply CDC batch for task " + taskId, ex);
        }
        for (RecordChangeEvent<SourceRecord> event : records) {
            committer.markProcessed(event);
        }
        committer.markBatchFinished();
    }

    @Override
    public void close() {
        running.set(false);
        applier.release();
    }
}
