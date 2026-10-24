package io.opendrs.engine.replay.filter;

import io.opendrs.engine.replay.relation.ColumnDefinition;

import java.util.Optional;

/**
 * Column Filter, used to filter out unnecessary columns
 * like system column or generated column
 *
 */
@FunctionalInterface
public interface ColumnFilter {
    /**
     * Build upsert sql statement
     *
     * @param columnDefinition column definition {@link ColumnDefinition}
     * @return a column definition or empty if the column should be filtered out
     */
    Optional<ColumnDefinition> apply(ColumnDefinition columnDefinition);
}
