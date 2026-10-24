package io.opendrs.engine.replay.dialect;

import io.opendrs.engine.replay.ReplayConfig;
import io.opendrs.engine.replay.annotation.ThreadSafe;
import org.apache.kafka.connect.errors.ConnectException;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.ServiceLoader;

/**
 * Database dialects, loads all dialects and
 * provides an interface to create a dialect
 *
 */
@ThreadSafe
public class DatabaseDialects {

    final static Map<String, DatabaseDialectProvider> DATABASE_DIALECT_REGISTRY = new LinkedHashMap<>();

    // load all database dialect providers
    static {
        loadDatabaseDialects();
    }

    private static void loadDatabaseDialects() {
        for (DatabaseDialectProvider databaseDialectProvider : ServiceLoader.load(DatabaseDialectProvider.class)) {
            DATABASE_DIALECT_REGISTRY.put(databaseDialectProvider.dialectName(), databaseDialectProvider);
        }
    }

    /**
     * create database dialect {@link DatabaseDialect} instance
     * with the given config {@link ReplayConfig}
     *
     * @param config connector config
     * @return database dialect
     */
    public static DatabaseDialect create(ReplayConfig config) {
        final String dialectName = config.getDatabaseDialectName();
        DatabaseDialectProvider dialectProvider = DATABASE_DIALECT_REGISTRY.get(dialectName);
        if (dialectProvider == null) {
            throw new ConnectException("database dialect \"" + dialectName + "\" is not found!");
        }
        return dialectProvider.create(config);
    }
}
