package io.opendrs.common.metadata;

/**
 * Supported database types. Constant names are the persisted / API contract
 * ({@code connection_info.type} and the web {@code DbType}); do not rename without
 * a data migration and a frontend change.
 */
public enum DatabaseType {
    MYSQL,
    ORACLE,
    POSTGRESQL
}
