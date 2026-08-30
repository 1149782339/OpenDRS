package io.opendrs.debezium;

import io.debezium.engine.DebeziumEngine;
import io.debezium.engine.RecordChangeEvent;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.connect.data.Struct;
import org.apache.kafka.connect.source.SourceRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Test {@link DebeziumEngine.ChangeConsumer} that records SourceRecords and counts down when a
 * matching data-change envelope arrives. Production still uses {@link LoggingChangeConsumer}.
 */
final class RecordingChangeConsumer implements DebeziumEngine.ChangeConsumer<RecordChangeEvent<SourceRecord>> {

    private static final Logger log = LoggerFactory.getLogger(RecordingChangeConsumer.class);

    private final long taskId;
    private final CountDownLatch dataChangeLatch = new CountDownLatch(1);
    private final List<SourceRecord> records = new CopyOnWriteArrayList<>();

    RecordingChangeConsumer(long taskId) {
        this.taskId = taskId;
    }

    @Override
    public void handleBatch(
            List<RecordChangeEvent<SourceRecord>> events,
            DebeziumEngine.RecordCommitter<RecordChangeEvent<SourceRecord>> committer)
            throws InterruptedException {
        for (RecordChangeEvent<SourceRecord> event : events) {
            SourceRecord record = event.record();
            records.add(record);
            Object op = LoggingChangeConsumer.extractOp(record);
            log.info(
                    "CDC record task={} topic={} key={} sourceOffset={} op={}",
                    taskId,
                    record.topic(),
                    record.key(),
                    record.sourceOffset(),
                    op);
            if (isTableDataChange(record)) {
                dataChangeLatch.countDown();
            }
            committer.markProcessed(event);
        }
        committer.markBatchFinished();
    }

    boolean awaitDataChange(long timeout, TimeUnit unit) throws InterruptedException {
        return dataChangeLatch.await(timeout, unit);
    }

    List<SourceRecord> records() {
        return List.copyOf(records);
    }

    SourceRecord firstTableDataChange() {
        return records.stream().filter(RecordingChangeConsumer::isTableDataChange).findFirst().orElse(null);
    }

    static boolean isTableDataChange(SourceRecord record) {
        Object op = LoggingChangeConsumer.extractOp(record);
        return "c".equals(op) || "r".equals(op) || "u".equals(op);
    }

    static Struct after(SourceRecord record) {
        Object value = record.value();
        if (value instanceof Struct envelope && envelope.schema().field("after") != null) {
            return envelope.getStruct("after");
        }
        return null;
    }
}
