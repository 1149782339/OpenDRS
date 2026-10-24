package io.opendrs.engine.replay.naming;

import io.opendrs.common.naming.ColumnNamingStrategy;

/**
 * Default column naming strategy without any changes
 *
 */
public class DefaultColumnNamingStrategy implements ColumnNamingStrategy {
    @Override
    public String resolveColumnName(String column) {
        return column;
    }
}
