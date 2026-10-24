package io.opendrs.engine.capture;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import javax.sql.DataSource;

/**
 * Task-scoped binding of the metadata {@link DataSource} for the stores that Debezium instantiates
 * reflectively from {@code offset.storage} / {@code schema.history.internal} (no-arg constructor, so
 * the DataSource cannot be injected through the constructor).
 *
 * <p>Lifetime is one Engine run: {@link DebeziumCdcEngineFactory#create} binds before the Engine
 * starts and the returned {@link CdcEngine} unbinds when it stops. No process-wide singleton and no
 * "initialize before use" contract.
 */
final class TaskDataSourceRegistry {

    private static final Map<String, DataSource> BY_ENGINE = new ConcurrentHashMap<>();

    private TaskDataSourceRegistry() {
    }

    /**
     * @param engineKey unique per engine run — the engine name. Bidirectional tasks run two
     *     engines on one task id, so the task id alone cannot key this map: the first direction
     *     to stop must not unbind the DataSource the other direction still resolves through.
     */
    static void bind(String engineKey, DataSource dataSource) {
        if (dataSource != null) {
            BY_ENGINE.put(engineKey, dataSource);
        }
    }

    static void unbind(String engineKey) {
        BY_ENGINE.remove(engineKey);
    }

    static DataSource get(String engineKey) {
        DataSource dataSource = BY_ENGINE.get(engineKey);
        if (dataSource == null) {
            throw new IllegalStateException("No metadata DataSource bound for engine " + engineKey);
        }
        return dataSource;
    }
}
