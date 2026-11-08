package io.opendrs.engine.datacheck.type.handler;

import io.opendrs.engine.common.metadata.DatabaseType;

/**
 * Picks the {@link TypeHandler} for the database a row is read from. The handlers are stateless,
 * so one instance per database type is shared.
 */
public final class TypeHandlerFactory {

    private static final TypeHandler MYSQL = new MySqlTypeHandler();
    private static final TypeHandler POSTGRESQL = new PostgreSqlTypeHandler();

    private TypeHandlerFactory() {
    }

    public static TypeHandler of(DatabaseType database) {
        return switch (database) {
            case MYSQL -> MYSQL;
            case POSTGRESQL -> POSTGRESQL;
            case ORACLE -> throw new IllegalArgumentException(
                    "Unsupported type handler: " + database);
        };
    }
}
