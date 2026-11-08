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

    @Override
    public java.lang.String toString() {
        return "null";
    }
}
