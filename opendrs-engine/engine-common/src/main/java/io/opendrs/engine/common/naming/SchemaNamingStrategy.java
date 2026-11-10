package io.opendrs.engine.common.naming;

/**
 * Schema naming strategy, used to resolve the schema name in the target database
 * from the source schema name.
 *
 * <p>Same shape as {@link ColumnNamingStrategy}: a pure name-to-name function, so the caller can
 * pass a lambda. Structure migration resolves it once per object and points the target connection
 * at the result before executing that object's DDL, which is what makes unqualified DDL from the
 * source land in the intended target schema.
 */
public interface SchemaNamingStrategy {

    /**
     * Resolve the source schema name into the target schema name.
     *
     * @param schema source schema name
     * @return target schema name
     */
    String resolveSchemaName(String schema);
}
