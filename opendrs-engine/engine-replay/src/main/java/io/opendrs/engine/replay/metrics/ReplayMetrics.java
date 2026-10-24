package io.opendrs.engine.replay.metrics;

/**
 * Per-task, per-direction pipeline counters, wired by the service (e.g. a Micrometer-backed
 * implementation). Default methods are no-ops so the engine modules stay free of
 * metrics dependencies and remain trivially testable.
 *
 * <p>Every method takes the pipeline's {@link ReplayDirection}: a bidirectional task runs two
 * pipelines under one task id, and the monitor splits every series by direction —
 * "writes land on the target" vs "writes land on the source".
 */
public interface ReplayMetrics {

    ReplayMetrics NONE = new ReplayMetrics() {
    };

    /** Change events captured from the source binlog, counted when a batch arrives. */
    default void captured(ReplayDirection direction, long taskId, int events) {
    }

    /**
     * One schedulable unit committed on the target. The unit is exactly one source transaction
     * when the events arrive through {@code TransactionApplier} (counted by the transaction
     * metadata END marker); full-load table chunks carry no transaction and count zero.
     * {@code execMillis}/{@code commitMillis} are that unit's own statement-execution and
     * commit wall time, so the averaged metric is a true per-transaction latency.
     */
    default void txCommitted(
            ReplayDirection direction, long taskId, int txCount, double execMillis, double commitMillis) {
    }

    /** A batch that failed after all apply retries were exhausted. */
    default void applyFailed(ReplayDirection direction, long taskId) {
    }

    /**
     * DML rows committed on the target in one apply batch. Counted per {@code addBatch()}'d
     * event — never per {@code executeBatch()} call, which collapses a whole batch into 1 —
     * and only after the batch's {@code commit()} lands, so a rolled-back or retried batch
     * contributes nothing.
     */
    default void appliedRows(ReplayDirection direction, long taskId, int rows) {
    }

    /**
     * The source timestamp (millis, source clock) of the most recently committed batch.
     * Implementations derive the live replay lag from this together with the commit wall
     * time, so the metric can decay when the source goes idle instead of freezing at the
     * last measured value.
     */
    default void replayLag(ReplayDirection direction, long taskId, long eventSourceTsMs) {
    }
}
