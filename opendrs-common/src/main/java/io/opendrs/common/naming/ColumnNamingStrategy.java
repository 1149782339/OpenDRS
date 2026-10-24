package io.opendrs.common.naming;

/**
 * Column naming strategy, used to resolve the column name in the target database
 * from the source column name.
 *
 */
public interface ColumnNamingStrategy {
    /**
     * Resolve the source column name into the target column name.
     *
     * @param column source column name
     * @return target column name
     */
    String resolveColumnName(String column);
}
