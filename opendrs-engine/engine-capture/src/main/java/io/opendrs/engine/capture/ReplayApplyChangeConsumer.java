package io.opendrs.engine.capture;

import io.debezium.engine.DebeziumEngine;
import io.debezium.engine.RecordChangeEvent;
import io.opendrs.common.log.MigrationLogListener;
import io.opendrs.common.progress.FullLoadCheckpointListener;
import io.opendrs.common.progress.MigrationLagListener;
import io.opendrs.engine.replay.GtidLoopConfig;
import io.opendrs.engine.replay.ReplayConfig;
import io.opendrs.engine.replay.applier.Applier;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import org.apache.kafka.connect.data.Struct;
import org.apache.kafka.connect.source.SourceRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * DIRECT change consumer: filters GTID loop-backs, applies through {@link BatchApplyHandler},
 * then marks Debezium offsets ({@code apply → markProcessed}). Production default path.
 */
public final class ReplayApplyChangeConsumer
        implements DebeziumEngine.ChangeConsumer<RecordChangeEvent<SourceRecord>>, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(ReplayApplyChangeConsumer.class);

    private final long taskId;
    private final ReplayConfig config;
    private final BatchApplyHandler applyHandler;

    public ReplayApplyChangeConsumer(long taskId, ReplayConfig config) {
        this(taskId, config, MigrationLogListener.NONE);
    }

    public ReplayApplyChangeConsumer(long taskId, ReplayConfig config, MigrationLogListener logListener) {
        this(taskId, config, logListener, MigrationLagListener.NONE);
    }

    public ReplayApplyChangeConsumer(
            long taskId,
            ReplayConfig config,
            MigrationLogListener logListener,
            MigrationLagListener positionListener) {
        this(taskId, config, logListener, positionListener, null);
    }

    public ReplayApplyChangeConsumer(
            long taskId,
            ReplayConfig config,
            MigrationLogListener logListener,
            MigrationLagListener positionListener,
            FullLoadCheckpointListener checkpointListener) {
        this(taskId, taskId, config, logListener, positionListener, checkpointListener, null,
                BatchApplyHandler.CHECKPOINT_RETRY_BACKOFF_MS);
    }

    /**
     * @param pipelineId the directional pipeline the checkpoints are recorded for; the engine
     *     factory passes {@code EngineSpec.pipelineId()}. Callers without a persisted pipeline
     *     (unit tests) fall back to the task id.
     */
    public ReplayApplyChangeConsumer(
            long taskId,
            long pipelineId,
            ReplayConfig config,
            MigrationLogListener logListener,
            MigrationLagListener positionListener,
            FullLoadCheckpointListener checkpointListener) {
        this(taskId, pipelineId, config, logListener, positionListener, checkpointListener, null,
                BatchApplyHandler.CHECKPOINT_RETRY_BACKOFF_MS);
    }

    /**
     * Test seam: injects the applier (a no-op fake applies nothing) and a short retry backoff
     * so checkpoint-trigger tests need not sleep. A null applier builds the production
     * RoutingApplier pipeline.
     */
    ReplayApplyChangeConsumer(
            long taskId,
            long pipelineId,
            ReplayConfig config,
            MigrationLogListener logListener,
            MigrationLagListener positionListener,
            FullLoadCheckpointListener checkpointListener,
            Applier<Collection<SourceRecord>> applier,
            long checkpointRetryBackoffMs) {
        this.taskId = taskId;
        this.config = config;
        this.applyHandler = new BatchApplyHandler(
                taskId, pipelineId, config, logListener, positionListener, checkpointListener,
                applier, checkpointRetryBackoffMs);
        log.debug("DIRECT ReplayApplyChangeConsumer ready for task {}", taskId);
    }

    @Override
    public void handleBatch(
            List<RecordChangeEvent<SourceRecord>> records,
            DebeziumEngine.RecordCommitter<RecordChangeEvent<SourceRecord>> committer)
            throws InterruptedException {
        List<SourceRecord> batch = filterLoopBacks(records);
        applyHandler.applyBatch(batch);
        for (RecordChangeEvent<SourceRecord> event : records) {
            committer.markProcessed(event);
        }
        committer.markBatchFinished();
    }

    @Override
    public void close() {
        applyHandler.close();
    }

    private List<SourceRecord> filterLoopBacks(List<RecordChangeEvent<SourceRecord>> records) {
        List<SourceRecord> batch = new ArrayList<>(records.size());
        GtidLoopConfig loop = config.getGtidLoop();
        for (RecordChangeEvent<SourceRecord> event : records) {
            SourceRecord record = event.record();
            if (loop != null && loop.isLoopBack(sourceGtid(record))) {
                continue;
            }
            batch.add(record);
        }
        return batch;
    }

    /**
     * Source GTID ('uuid:seq') of a live binlog record, null for snapshots and heartbeats.
     * Snapshot rows carry no GTID and are never filtered — they are the task's own full load.
     */
    private static String sourceGtid(SourceRecord record) {
        Object value = record.value();
        if (!(value instanceof Struct struct) || struct.schema().field("source") == null) {
            return null;
        }
        Struct source = struct.getStruct("source");
        if (source == null || source.schema().field("gtid") == null) {
            return null;
        }
        return source.getString("gtid");
    }
}
