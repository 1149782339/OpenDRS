package io.opendrs.engine.datacheck.type.serialize;

/**
 * Picks the {@link Serializer} for the target database. A serializer holds the table-scoped
 * {@link SerializeConfiguration}, so one instance is created per table, not shared.
 */
public final class SerializerFactory {

    private SerializerFactory() {
    }

    public static Serializer of(SerializeConfiguration configuration) {
        return switch (configuration.targetDatabase()) {
            case POSTGRESQL -> new PostgreSqlSerializer(configuration);
            case MYSQL, ORACLE -> throw new IllegalArgumentException(
                    "Unsupported target serializer: " + configuration.targetDatabase());
        };
    }
}
