package io.opendrs.common.log;

/**
 * Observer for the parts of a run that only the engine can see: which tables the selection expanded
 * to, which DDL statements landed, when the snapshot turned into a stream, and why a CDC batch was
 * rejected.
 *
 * <p>The engine never persists anything itself. It hands messages to this listener and the caller
 * decides what to do with them - the service buffers them and writes {@code migration_log} rows.
 * That keeps the engine free of any storage dependency while still reporting what happened inside
 * it, the same way the phase listener reports phase changes.
 *
 * <p>Messages are plain technical English, matching the rest of the engine's diagnostics. They are
 * shown verbatim in the task log, so they must not carry secrets: a message may name a table or an
 * offset, never a password or a full JDBC URL.
 *
 * <p>Implementations are called from whichever engine thread produced the event - the schema
 * migrator, the snapshot, or the Debezium change consumer - so they must be thread-safe and must
 * not block on slow work: a synchronous database write here would stall the data path.
 */
@FunctionalInterface
public interface MigrationLogListener {

    /** No-op listener, used when the caller does not care. Never {@code null}, so call sites
     * do not branch. */
    MigrationLogListener NONE = (level, message) -> {
    };

    void onLog(MigrationLogLevel level, String message);

    /**
     * Emits and swallows. A broken sink must never take the migration down with it: the engine
     * calls this everywhere, and {@link #onLog} is only invoked directly by tests.
     */
    default void emit(MigrationLogLevel level, String message) {
        try {
            onLog(level, message);
        } catch (RuntimeException ignored) {
            // Observability is a side channel: dropping a line beats failing a run that is
            // otherwise healthy. The sink logs its own failures.
        }
    }
}
