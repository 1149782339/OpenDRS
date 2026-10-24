package io.opendrs.common.progress;

/**
 * Observer for "how far behind the target is", fed from the capture path.
 *
 * <p>Lag is defined the way CDC tools define it: the wall-clock distance between <em>now</em> and
 * the moment the last change was written in the <em>source</em> (Debezium's
 * {@code source.ts_ms}, i.e. the binlog event timestamp). That is the only timestamp that measures
 * the pipeline rather than the connector; Debezium's envelope {@code ts_ms} is when the connector
 * read the record, which would report a healthy pipeline on a stalled sink.
 *
 * <p>This interface deliberately carries absolute timestamps instead of a computed lag. A consumer
 * that stores the difference freezes at its last healthy value the moment the pipeline stalls; a
 * consumer that stores {@code sourceEventTimeMs} can subtract <em>now</em> at read time and watch
 * the number grow, which is exactly what an operator needs to see.
 *
 * <p>Called on the Debezium change-consumer thread, so implementations must be thread-safe and
 * must not block. Like {@link io.opendrs.common.log.MigrationLogListener}, a broken sink must never
 * fail a healthy migration: {@link #emitApplied} and {@link #emitHeartbeat} swallow exceptions.
 */
@FunctionalInterface
public interface MigrationLagListener {

    /** Does nothing; the default for runs nobody is watching. */
    MigrationLagListener NONE = (sourceEventTimeMs, appliedAtMs) -> { };

    /**
     * A batch was committed to the target.
     *
     * @param sourceEventTimeMs the source timestamp of the newest change in the batch, from the
     *     source server's clock (MySQL binlog timestamps are whole seconds)
     * @param appliedAtMs when the commit finished, from this host's clock
     */
    void onApplied(long sourceEventTimeMs, long appliedAtMs);

    /**
     * The connector is alive but no change was applied: Debezium emits a heartbeat every
     * {@code heartbeat.interval.ms} even when the source is quiet. This is what separates "source
     * idle, target caught up" from "pipeline stuck", which the event timestamps alone cannot do.
     *
     * @param seenAtMs when the heartbeat was consumed, from this host's clock
     */
    default void onHeartbeat(long seenAtMs) {
    }

    /** {@link #onApplied} that cannot break the caller. */
    default void emitApplied(long sourceEventTimeMs, long appliedAtMs) {
        try {
            onApplied(sourceEventTimeMs, appliedAtMs);
        } catch (RuntimeException ignored) {
            // A missing lag sample is a missing gauge, not a failed migration.
        }
    }

    /** {@link #onHeartbeat} that cannot break the caller. */
    default void emitHeartbeat(long seenAtMs) {
        try {
            onHeartbeat(seenAtMs);
        } catch (RuntimeException ignored) {
            // Same trade as emitApplied.
        }
    }

    /**
     * How far the capture has read, as a display position ("mysql-bin.000007:197").
     * Published when a batch arrives from the connector, before it is applied.
     */
    default void onCaptured(String position, long atMs) {
    }

    /**
     * How far the target has applied, same format as {@link #onCaptured}. Published after
     * the batch commit succeeds.
     */
    default void onAppliedPosition(String position, long atMs) {
    }

    /** {@link #onCaptured} that cannot break the caller. */
    default void emitCaptured(String position, long atMs) {
        try {
            onCaptured(position, atMs);
        } catch (RuntimeException ignored) {
            // A missing position sample is a missing label, not a failed migration.
        }
    }

    /** {@link #onAppliedPosition} that cannot break the caller. */
    default void emitAppliedPosition(String position, long atMs) {
        try {
            onAppliedPosition(position, atMs);
        } catch (RuntimeException ignored) {
            // Same trade as emitCaptured.
        }
    }
}
