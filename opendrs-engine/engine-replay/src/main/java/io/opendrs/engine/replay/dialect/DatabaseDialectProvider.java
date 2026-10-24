package io.opendrs.engine.replay.dialect;

import io.opendrs.engine.replay.ReplayConfig;

/**
 * Database dialect factory class
 *
 */
public interface DatabaseDialectProvider {
    /**
     * Create database dialect
     *
     * @param config sink config {@link ReplayConfig}
     * @return database dialect {@link DatabaseDialect}
     */
    DatabaseDialect create(ReplayConfig config);
    /**
     * Database dialect name
     *
     * @return database dialect name {@link String}
     */
    String dialectName();
}
