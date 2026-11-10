package io.opendrs.engine.datacheck.incremental;

import io.opendrs.engine.common.metadata.TableId;

@FunctionalInterface
public interface LagSink {

    void update(TableId sourceTable, long lagMs);

    default void update(TableId sourceTable, long lagMs, boolean waitingForReplay) {
        update(sourceTable, lagMs);
    }

    /** Called once after a batch of per-table lag updates (task-level lag = max of tables). */
    default void flush() {
    }
}
