package io.opendrs.engine.jdbc;

/**
 * A named variable in a JDBC URL pattern, optionally with a default value. Mirrors the small part
 * of Debezium's {@code io.debezium.config.Field} that the pattern based factory needs: a name and
 * a default, so a connector can pin "port defaults to 5432" without hard coding it in the pattern.
 */
public final class Field {

    private final String name;
    private final String defaultValue;

    private Field(String name, String defaultValue) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("field name is required");
        }
        this.name = name;
        this.defaultValue = defaultValue;
    }

    public static Field of(String name) {
        return new Field(name, null);
    }

    public static Field of(String name, String defaultValue) {
        return new Field(name, defaultValue);
    }

    /** A copy of this field with the given default. */
    public Field withDefault(String defaultValue) {
        return new Field(name, defaultValue);
    }

    public String name() {
        return name;
    }

    public String defaultValueAsString() {
        return defaultValue;
    }
}
