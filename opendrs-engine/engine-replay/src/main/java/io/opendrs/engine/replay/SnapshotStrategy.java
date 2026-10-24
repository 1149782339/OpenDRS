package io.opendrs.engine.replay;

/**
 * How the capture engine treats the source's existing data, i.e. the sync mode expressed in
 * framework-free terms. The capture module maps each value onto its connector's snapshot mode;
 * the binlog/MySQL capture maps them to Debezium's {@code snapshot.mode}:
 *
 * <ul>
 *   <li>{@link #INITIAL} — copy schema + all rows, then follow the change log (全量+增量,
 *       Debezium {@code initial})</li>
 *   <li>{@link #NO_DATA} — snapshot schemas only, then follow the log from the snapshot position
 *       (增量, Debezium {@code no_data}, formerly {@code schema_only})</li>
 *   <li>{@link #INITIAL_ONLY} — copy schema + all rows, then stop before reading the log
 *       (全量, Debezium {@code initial_only}); the engine ends by itself and the run is a
 *       finite job that lands in {@code TaskState.COMPLETED}</li>
 * </ul>
 *
 * <p>Lives in engine-replay because engine-capture already depends on this module and
 * engine-core must not be a capture dependency.
 */
public enum SnapshotStrategy {

    INITIAL,
    NO_DATA,
    INITIAL_ONLY
}
