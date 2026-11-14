package io.opendrs.migration.job;

import io.opendrs.common.progress.MigrationLagListener;
import io.opendrs.engine.jdbc.binlog.BinlogPosition;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Watches the applied source offsets of one pipeline for the snapshot end position (P1) and fires
 * once when replay has passed it. Until then a unique key or foreign key cannot come back: the
 * snapshot chunks were read at different points between P0 and P1, so the target only matches
 * one consistent source state once every change up to P1 is applied.
 *
 * <p>Wraps the pipeline's lag listener and forwards everything to it. Called on the apply thread,
 * so {@code onReached} must only hand the work off.
 */
final class SnapshotEndWatcher implements MigrationLagListener {

    private final MigrationLagListener delegate;
    private final Runnable onReached;
    private final AtomicBoolean fired = new AtomicBoolean(false);
    private volatile BinlogPosition target;

    SnapshotEndWatcher(MigrationLagListener delegate, Runnable onReached) {
        this.delegate = delegate == null ? MigrationLagListener.NONE : delegate;
        this.onReached = onReached;
    }

    /** Starts watching for {@code snapshotEnd}; offsets applied before this are not looked at. */
    void arm(BinlogPosition snapshotEnd) {
        this.target = snapshotEnd;
    }

    boolean armed() {
        return target != null;
    }

    boolean fired() {
        return fired.get();
    }

    @Override
    public void onAppliedOffset(Map<String, ?> sourceOffset, long atMs) {
        delegate.onAppliedOffset(sourceOffset, atMs);
        BinlogPosition watched = target;
        if (watched != null && !fired.get() && watched.reachedBy(sourceOffset) && fired.compareAndSet(false, true)) {
            onReached.run();
        }
    }

    @Override
    public void onApplied(long sourceEventTimeMs, long appliedAtMs) {
        delegate.onApplied(sourceEventTimeMs, appliedAtMs);
    }

    @Override
    public void onHeartbeat(long seenAtMs) {
        delegate.onHeartbeat(seenAtMs);
    }

    @Override
    public void onCaptured(String position, long atMs) {
        delegate.onCaptured(position, atMs);
    }

    @Override
    public void onCapturedEvent(String position, long sourceEventTimeMs, long atMs) {
        delegate.onCapturedEvent(position, sourceEventTimeMs, atMs);
    }

    @Override
    public void onAppliedPosition(String position, long atMs) {
        delegate.onAppliedPosition(position, atMs);
    }

    @Override
    public void onProduced(int partition, long offset, long createTimeMs) {
        delegate.onProduced(partition, offset, createTimeMs);
    }

    @Override
    public void onReplayed(int partition, long offset, long createTimeMs) {
        delegate.onReplayed(partition, offset, createTimeMs);
    }

    @Override
    public void onLogEnd(int partition, long endOffsetExclusive, long atMs) {
        delegate.onLogEnd(partition, endOffsetExclusive, atMs);
    }
}
