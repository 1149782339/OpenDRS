package io.opendrs.engine.capture;

import io.opendrs.engine.common.log.MigrationLogLevel;
import io.opendrs.engine.common.log.MigrationLogListener;
import io.opendrs.common.progress.FullLoadCheckpointListener;
import io.opendrs.common.progress.MigrationLagListener;
import io.opendrs.engine.replay.ReplayConfig;
import io.opendrs.engine.replay.applier.Applier;
import io.opendrs.engine.replay.applier.ChangeEventApplier;
import io.opendrs.engine.replay.applier.RoutingApplier;
import io.opendrs.engine.replay.context.ConnectorContext;
import io.opendrs.engine.replay.context.TaskContext;
import io.opendrs.engine.replay.exception.ApplierException;
import io.opendrs.engine.replay.util.ErrorHandler;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.kafka.connect.data.Struct;
import org.apache.kafka.connect.source.SourceRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Unified replay apply entry: convert/apply a batch of {@link SourceRecord}s onto the target,
 * emit lag/checkpoint side effects, then return. Callers decide when to advance capture offsets
 * ({@code debezium_offsets}) or applied offsets ({@code pipeline_applied_offsets}).
 *
 * <p>DIRECT capture uses this before {@code markProcessed}; KAFKA replay uses the same path after
 * consuming from the pipeline topic.
 */
public final class BatchApplyHandler implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(BatchApplyHandler.class);

    static final int CHECKPOINT_MAX_ATTEMPTS = 3;
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
    private final boolean reportCapturePosition;
    private final boolean countCapturedEvents;
    private final AtomicBoolean running = new AtomicBoolean(true);

    public BatchApplyHandler(
            long taskId,
            long pipelineId,
            ReplayConfig config,
            MigrationLogListener logListener,
            MigrationLagListener positionListener,
            FullLoadCheckpointListener checkpointListener) {
        this(taskId, pipelineId, config, logListener, positionListener, checkpointListener, true, true);
    }

    /**
     * @param reportCapturePosition when false (KAFKA incremental replay), capture binlog
     *     position and the capture-events counter are left to the produce path
     * @param countCapturedEvents when false, {@code metrics.captured} is not incremented here
     */
    public BatchApplyHandler(
            long taskId,
            long pipelineId,
            ReplayConfig config,
            MigrationLogListener logListener,
            MigrationLagListener positionListener,
            FullLoadCheckpointListener checkpointListener,
            boolean reportCapturePosition,
            boolean countCapturedEvents) {
        this(taskId, pipelineId, config, logListener, positionListener, checkpointListener, null,
                CHECKPOINT_RETRY_BACKOFF_MS, reportCapturePosition, countCapturedEvents);
    }

    /**
     * Test seam: inject the applier and a short retry backoff.
     */
    BatchApplyHandler(
            long taskId,
            long pipelineId,
            ReplayConfig config,
            MigrationLogListener logListener,
            MigrationLagListener positionListener,
            FullLoadCheckpointListener checkpointListener,
            Applier<Collection<SourceRecord>> applier,
            long checkpointRetryBackoffMs) {
        this(taskId, pipelineId, config, logListener, positionListener, checkpointListener, applier,
                checkpointRetryBackoffMs, true, true);
    }

    BatchApplyHandler(
            long taskId,
            long pipelineId,
            ReplayConfig config,
            MigrationLogListener logListener,
            MigrationLagListener positionListener,
            FullLoadCheckpointListener checkpointListener,
            Applier<Collection<SourceRecord>> applier,
            long checkpointRetryBackoffMs,
            boolean reportCapturePosition,
            boolean countCapturedEvents) {
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
            this.applier = new ChangeEventApplier(new RoutingApplier(context, errorHandler, config), config);
        }
        this.checkpointRetryBackoffMs = checkpointRetryBackoffMs;
        this.reportCapturePosition = reportCapturePosition;
        this.countCapturedEvents = countCapturedEvents;
        this.applier.prepare(TaskContext.empty());
    }

    /**
     * Applies {@code batch} to the target. Empty batches are a no-op. On failure throws
     * {@link IllegalStateException} wrapping the applier cause (same contract as the former
     * inline path in {@link ReplayApplyChangeConsumer}).
     */
    public void applyBatch(List<SourceRecord> batch) throws InterruptedException {
        if (batch == null || batch.isEmpty()) {
            return;
        }
        if (countCapturedEvents) {
            config.getMetrics().captured(config.getDirection(), taskId, batch.size());
        }
        if (reportCapturePosition) {
            reportCaptured(batch);
        }
        try {
            errorHandler.checkFailure();
            applier.apply(batch);
            reportFullLoadCheckpoints(batch);
            Long lastEventTsMs = lastEventSourceTsMs(batch);
            if (lastEventTsMs != null) {
                config.getMetrics().replayLag(config.getDirection(), taskId, lastEventTsMs);
            }
            emitLag(batch);
            String appliedPosition = positionOf(batch);
            if (appliedPosition != null) {
                positionListener.emitAppliedPosition(appliedPosition, System.currentTimeMillis());
            }
        }
        catch (ApplierException ex) {
            logListener.emit(MigrationLogLevel.ERROR, describeRejection(batch, ex));
            throw new IllegalStateException("Failed to apply CDC batch for task " + taskId, ex);
        }
    }

    public ErrorHandler errorHandler() {
        return errorHandler;
    }

    @Override
    public void close() {
        running.set(false);
        applier.release();
    }

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
            if (scan.isPresent() && scan.get().succeeded()) {
                onTableFinishedWithRetry(scan.get());
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
                    log.error("failed to persist for full load task {} for table {} ({} rows), ignore it",
                            this.taskId, scan.table(), scan.totalRows(), ex);
                    logListener.emit(MigrationLogLevel.ERROR,
                            "failed to persist full load task " + this.taskId + " for table " + scan.table()
                                    + " (" + scan.totalRows() + " rows)");
                    return;
                }
                log.warn("Failed to persist checkpoint for full task {} table \"{}\", retrying {}/{}",
                        this.taskId, table, attempt, CHECKPOINT_MAX_ATTEMPTS);
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


    private void reportCaptured(List<SourceRecord> batch) {
        String capturedPosition = positionOf(batch);
        long now = System.currentTimeMillis();
        Long eventTime = lastStreamingEventTimeMs(batch);
        if (capturedPosition != null) {
            if (eventTime != null) {
                positionListener.emitCapturedEvent(capturedPosition, eventTime, now);
            }
            else {
                positionListener.emitCaptured(capturedPosition, now);
            }
        }
        else if (eventTime != null) {
            positionListener.emitCapturedEvent(null, eventTime, now);
        }
        if (eventTime == null && hasHeartbeat(batch)) {
            positionListener.emitHeartbeat(now);
        }
    }

    private static Long lastStreamingEventTimeMs(List<SourceRecord> batch) {
        Long newest = null;
        for (SourceRecord record : batch) {
            Long eventTime = SourceEventTimes.streamingEventTimeMs(record);
            if (eventTime != null && (newest == null || eventTime > newest)) {
                newest = eventTime;
            }
        }
        return newest;
    }

    private static boolean hasHeartbeat(List<SourceRecord> batch) {
        for (SourceRecord record : batch) {
            if (SourceEventTimes.isHeartbeat(record)) {
                return true;
            }
        }
        return false;
    }

    private void emitLag(List<SourceRecord> batch) {
        long newest = Long.MIN_VALUE;
        boolean heartbeat = false;
        for (SourceRecord record : batch) {
            Long eventTime = SourceEventTimes.streamingEventTimeMs(record);
            if (eventTime != null) {
                newest = Math.max(newest, eventTime);
            }
            else if (SourceEventTimes.isHeartbeat(record)) {
                heartbeat = true;
            }
        }
        long now = System.currentTimeMillis();
        if (newest != Long.MIN_VALUE) {
            positionListener.emitApplied(newest, now);
        }
        else if (heartbeat) {
            positionListener.emitHeartbeat(now);
        }
    }

    public static String positionOf(List<SourceRecord> batch) {
        for (int i = batch.size() - 1; i >= 0; i--) {
            Object file = batch.get(i).sourceOffset().get("file");
            Object pos = batch.get(i).sourceOffset().get("pos");
            if (file instanceof String f && pos instanceof Number p) {
                return f + ":" + p.longValue();
            }
        }
        return null;
    }

    static Long lastEventSourceTsMs(List<SourceRecord> batch) {
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

    static String describeRejection(List<SourceRecord> batch, ApplierException ex) {
        if (batch.isEmpty()) {
            return "failed to apply an empty CDC batch: " + ex.getMessage();
        }
        SourceRecord first = batch.get(0);
        return "failed to apply " + batch.size() + " CDC record(s) starting at " + first.topic()
                + " offset " + first.sourceOffset() + ": " + ex.getMessage();
    }
}
