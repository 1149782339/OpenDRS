package io.opendrs.engine.capture;

import io.debezium.engine.DebeziumEngine;
import io.debezium.engine.RecordChangeEvent;
import io.opendrs.common.log.MigrationLogLevel;
import io.opendrs.common.log.MigrationLogListener;
import io.opendrs.common.progress.FullLoadCheckpointListener;
import io.opendrs.common.progress.MigrationLagListener;
import io.opendrs.engine.replay.GtidLoopConfig;
import io.opendrs.engine.replay.ReplayConfig;
import io.opendrs.engine.replay.applier.Applier;
import io.opendrs.engine.replay.applier.ChangeEventApplier;
import io.opendrs.engine.replay.applier.RoutingApplier;
import io.opendrs.engine.replay.context.ConnectorContext;
import io.opendrs.engine.replay.context.TaskContext;
import io.opendrs.engine.replay.exception.ApplierException;
import io.opendrs.engine.replay.util.ErrorHandler;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.kafka.connect.data.Struct;
import org.apache.kafka.connect.source.SourceRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Production change consumer: converts Debezium {@link SourceRecord}s and applies them through
 * {@code engine-replay} onto the task target.
 */
public final class ReplayApplyChangeConsumer
        implements DebeziumEngine.ChangeConsumer<RecordChangeEvent<SourceRecord>>, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(ReplayApplyChangeConsumer.class);

    /** Total attempts for one checkpoint write; the WARN log counts the retries as n/3. */
    static final int CHECKPOINT_MAX_ATTEMPTS = 3;

    /** Pause between checkpoint retries; 1s keeps a transient metadata-DB blip from failing a task. */
    static final long CHECKPOINT_RETRY_BACKOFF_MS = 1000;

    private final long taskId;
    private final long pipelineId;
    private final ReplayConfig config;
    private final ErrorHandler errorHandler;
    private final Applier<Collection<SourceRecord>> applier;
    private final MigrationLogListener logListener;
    private final MigrationLagListener positionListener;
    private final FullLoadCheckpointListener checkpointListener;
    private final long checkpointRetryBackoffMs;
    private final AtomicBoolean running = new AtomicBoolean(true);

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
                CHECKPOINT_RETRY_BACKOFF_MS);
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
                CHECKPOINT_RETRY_BACKOFF_MS);
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
        this.pipelineId = pipelineId;
        this.config = config;
        this.logListener = logListener == null ? MigrationLogListener.NONE : logListener;
        this.positionListener = positionListener == null ? MigrationLagListener.NONE : positionListener;
        this.checkpointListener = checkpointListener == null ? FullLoadCheckpointListener.NONE : checkpointListener;
        this.errorHandler = new ErrorHandler();
        ConnectorContext context = running::get;
        if (applier != null) {
            this.applier = applier;
        }
        else {
            // RoutingApplier splits READ rows (snapshot) to the table-parallel applier and
            // everything else to the transaction applier; both drain before apply() returns, so
            // offset bookkeeping below stays exactly as the serial path had it.
            this.applier = new ChangeEventApplier(new RoutingApplier(context, errorHandler, config), config);
        }
        this.checkpointRetryBackoffMs = checkpointRetryBackoffMs;
        this.applier.prepare(TaskContext.empty());
    }

    @Override
    public void handleBatch(
            List<RecordChangeEvent<SourceRecord>> records,
            DebeziumEngine.RecordCommitter<RecordChangeEvent<SourceRecord>> committer)
            throws InterruptedException {
        List<SourceRecord> batch = new ArrayList<>(records.size());
        GtidLoopConfig loop = config.getGtidLoop();
        for (RecordChangeEvent<SourceRecord> event : records) {
            SourceRecord record = event.record();
            if (loop != null && loop.isLoopBack(sourceGtid(record))) {
                // Write our opposite direction already applied (its GTID UUID is in the drop set).
                // Skip replaying it back — and skip logging it: a hot bidirectional pair would
                // otherwise drown the log in dropped rows.
                continue;
            }
            batch.add(record);
        }
        if (!batch.isEmpty()) {
            config.getMetrics().captured(config.getDirection(), taskId, batch.size());
            String position = positionOf(batch);
            if (position != null) {
                positionListener.emitCaptured(position, System.currentTimeMillis());
            }
        }
        try {
            // A worker may have failed while this thread was handing the previous batch back
            // to the engine; fail here with the original cause instead of applying more work.
            errorHandler.checkFailure();
            applier.apply(batch);
            reportFullLoadCheckpoints(batch);
            Long lastEventTsMs = lastEventSourceTsMs(batch);
            if (lastEventTsMs != null) {
                config.getMetrics().replayLag(config.getDirection(), taskId, lastEventTsMs);
            }
            String position = positionOf(batch);
            if (position != null) {
                positionListener.emitAppliedPosition(position, System.currentTimeMillis());
            }
        } catch (ApplierException ex) {
            // Only this layer knows which records were in flight when the target refused them.
            // The exception alone reaches the UI as "data migration failed"; this line says how
            // much was lost and where the batch started, which is what an operator needs to
            // decide whether a resume is safe.
            logListener.emit(MigrationLogLevel.ERROR, describeRejection(batch, ex));
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

    /**
     * Scans a committed batch for snapshot notifications: the resolved table list is narrated
     * to the task log (observability only), a table's {@code TABLE_SCAN_COMPLETED} is persisted
     * through the checkpoint listener. Runs after {@code applier.apply(batch)} returns: Debezium
     * emits a table's {@code TABLE_SCAN_COMPLETED} only after all of that table's rows, and the
     * applier commits synchronously before returning, so "notification visible in this batch"
     * means "table committed on the target". Debezium's ordering guarantee, not a heuristic.
     *
     * <p>A persist failure that exhausts the retries is logged at ERROR and otherwise ignored:
     * the batch's rows are already committed, so failing the task would throw away good data for
     * a metadata glitch. The missing done mark means a later resume re-exports the table
     * (idempotent full load) — self-healing by re-export.
     */
    private void reportFullLoadCheckpoints(List<SourceRecord> batch) throws InterruptedException {
        for (SourceRecord record : batch) {
            Optional<List<String>> resolved = SnapshotNotifications.parseDataCollectionsResolved(record);
            if (resolved.isPresent()) {
                logListener.emit(MigrationLogLevel.INFO,
                        "full load table list resolved: " + resolved.get().size() + " table(s)");
                continue;
            }
            Optional<SnapshotNotifications.TableScanCompleted> scan =
                    SnapshotNotifications.parseTableScanCompleted(record);
            if (scan.isPresent()) {
                if (scan.get().succeeded()) {
                    onTableFinishedWithRetry(scan.get());
                }
            }
        }
    }

    private void onTableFinishedWithRetry(SnapshotNotifications.TableScanCompleted scan)
            throws InterruptedException {
        String table = scan.table();
        for (int attempt = 1; ; attempt++) {
            try {
                checkpointListener.onTableFinished(pipelineId, table, scan.totalRows());
                log.info("full load finished for table {} ({} rows)", scan.table(), scan.totalRows());
                logListener.emit(MigrationLogLevel.INFO,
                        "full load finished for table " + scan.table()
                                + " (" + scan.totalRows() + " rows)");
                return;
            }
            catch (RuntimeException ex) {
                if (attempt >= CHECKPOINT_MAX_ATTEMPTS) {
                    log.error("failed to persist for full load task {} for table {} ({} rows), ignore it",this.taskId, scan.table(), scan.totalRows(), ex);
                    logListener.emit(MigrationLogLevel.ERROR,
                            "failed to persist full load task " + this.taskId + " for table " + scan.table()
                                    + " (" + scan.totalRows() + " rows)");
                    return;
                }
                log.warn("Failed to persist checkpoint for full task {} table \"{}\", retrying {}/{}", this.taskId, table, attempt, CHECKPOINT_MAX_ATTEMPTS);
                sleepBeforeCheckpointRetry();
            }
        }
    }

    private void sleepBeforeCheckpointRetry() throws InterruptedException {
        try {
            Thread.sleep(checkpointRetryBackoffMs);
        }
        catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw ex;
        }
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

    /**
     * Display position ("mysql-bin.000007:197") of the last record in the batch that carries a
     * binlog file offset, or null while the connector is still in the snapshot phase (snapshot
     * offsets have no {@code file}/{@code pos}).
     */
    private static String positionOf(List<SourceRecord> batch) {        for (int i = batch.size() - 1; i >= 0; i--) {
            Object file = batch.get(i).sourceOffset().get("file");
            Object pos = batch.get(i).sourceOffset().get("pos");
            if (file instanceof String f && pos instanceof Number p) {
                return f + ":" + p.longValue();
            }
        }
        return null;
    }

    /**
     * Source {@code ts_ms} (binlog event time) of the last data record in the batch, or
     * null when no record carries a usable timestamp (e.g. an all-DDL batch or tombstones).
     */
    private static Long lastEventSourceTsMs(List<SourceRecord> batch) {
        for (int i = batch.size() - 1; i >= 0; i--) {
            Object value = batch.get(i).value();
            if (value instanceof Struct struct && struct.schema().field("source") != null) {
                Struct source = struct.getStruct("source");
                if (source != null && source.schema().field("ts_ms") != null) {
                    Long tsMs = source.getInt64("ts_ms");
                    if (tsMs != null && tsMs > 0) {
                        return tsMs;
                    }
                }
            }
        }
        return null;
    }

    /**
     * Names the batch and its starting position. {@code topic} is Debezium's
     * {@code server.database.table}, which is the only place the source table of a rejected record
     * is visible on this side.
     */
    private static String describeRejection(List<SourceRecord> batch, ApplierException ex) {        if (batch.isEmpty()) {
            return "failed to apply an empty CDC batch: " + ex.getMessage();
        }
        SourceRecord first = batch.get(0);
        return "failed to apply " + batch.size() + " CDC record(s) starting at " + first.topic()
                + " offset " + first.sourceOffset() + ": " + ex.getMessage();
    }
}
