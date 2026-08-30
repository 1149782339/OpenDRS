package io.opendrs.debezium;

import io.debezium.engine.DebeziumEngine;
import io.debezium.engine.RecordChangeEvent;
import java.util.List;
import org.apache.kafka.connect.data.Struct;
import org.apache.kafka.connect.source.SourceRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Logs SourceRecords only. No JDBC apply to PostgreSQL in this batch.
 */
public class LoggingChangeConsumer implements DebeziumEngine.ChangeConsumer<RecordChangeEvent<SourceRecord>> {

    private static final Logger log = LoggerFactory.getLogger(LoggingChangeConsumer.class);

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
            SourceRecord record = event.record();
            log.info(
                    "CDC record task={} topic={} key={} sourceOffset={} op={}",
                    taskId,
                    record.topic(),
                    record.key(),
                    record.sourceOffset(),
                    extractOp(record));
            committer.markProcessed(event);
        }
        committer.markBatchFinished();
    }

    static Object extractOp(SourceRecord record) {
        Object value = record.value();
        if (value instanceof Struct struct && struct.schema().field("op") != null) {
            return struct.get("op");
        }
        return null;
    }
}
