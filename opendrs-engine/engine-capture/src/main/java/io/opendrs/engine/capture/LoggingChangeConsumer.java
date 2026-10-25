package io.opendrs.engine.capture;

import io.debezium.engine.DebeziumEngine;
import io.debezium.engine.RecordChangeEvent;
import java.util.List;
import org.apache.kafka.connect.data.Struct;
import org.apache.kafka.connect.source.SourceRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Pass-through SourceRecord consumer for the no-target path (nothing is applied, so there is
 * nothing to narrate per record — the old per-record INFO line drowned the log on any real
 * table set). Production apply uses {@link ReplayApplyChangeConsumer}; this class also remains
 * as {@link #extractOp} for tests and the snapshot-completion watcher.
 */
public class LoggingChangeConsumer implements DebeziumEngine.ChangeConsumer<RecordChangeEvent<SourceRecord>> {

    private final long taskId;

    public LoggingChangeConsumer(long taskId) {
        this.taskId = taskId;
    }

    @Override
    public void handleBatch(
            List<RecordChangeEvent<SourceRecord>> records,
            DebeziumEngine.RecordCommitter<RecordChangeEvent<SourceRecord>> committer)
            throws InterruptedException {
        for (RecordChangeEvent<SourceRecord> event : records) {
            committer.markProcessed(event);
        }
        committer.markBatchFinished();
    }

    public static Object extractOp(SourceRecord record) {
        Object value = record.value();
        if (value instanceof Struct struct && struct.schema().field("op") != null) {
            return struct.get("op");
        }
        return null;
    }
}
