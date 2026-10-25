package io.opendrs.engine.capture;

import io.debezium.snapshot.mode.InitialSnapshotter;

/**
 * Initial snapshot for a resumed full load: same schema+data snapshot decision as Debezium's
 * {@code initial} mode, but streaming resumes from the stored (P0) binlog position instead of
 * from the snapshot start — the rows snapshotted before the crash are replayed from the log
 * between P0 and the resume point. Debezium's default {@code shouldStreamEventsStartingFromSnapshot}
 * would stream from the snapshot marker and duplicate them.
 *
 * <p>Activated only on resume runs: {@code DebeziumEngineConfig.capture} sets
 * {@code snapshot.mode=custom} + {@code snapshot.mode.custom.name=opendrs_resumable} together
 * with {@code snapshot.include.collection.list} (the remaining tables), which routes Debezium's
 * Snapshotter ServiceLoader here. The name must stay distinct from Debezium's own
 * {@code initial} snapshotter: same name would make the ServiceLoader match both and fail.
 */
public class ResumableInitialSnapshotter extends InitialSnapshotter {

    public static final String NAME = "opendrs_resumable";

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public boolean shouldStreamEventsStartingFromSnapshot() {
        return false;
    }
}
