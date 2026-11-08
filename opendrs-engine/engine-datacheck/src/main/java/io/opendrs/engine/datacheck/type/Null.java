package io.opendrs.engine.datacheck.type;

public record Null() implements LogicalValue {
    public static final Null INSTANCE = new Null();

    @Override
    public LogicalType type() {
        return LogicalType.NULL;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Null;
    }

    @Override
    public int hashCode() {
        return LogicalType.NULL.hashCode();
    }

    /**
     * A SQL NULL contributes no text: writing {@code "null"} would collide with the literal string
     * {@code "null"}, and the column boundary is carried by the caller's separator.
     */
    @Override
    public void serialize(StringBuilder builder) {
        // deliberately empty
    }

    @Override
    public java.lang.String toString() {
        return "null";
    }
}
