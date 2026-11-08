package io.opendrs.engine.datacheck.type;

public record Long(long value) implements LogicalValue {
    @Override
    public LogicalType type() {
        return LogicalType.LONG;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Long(long other))) {
            return false;
        }
        return value == other;
    }

    @Override
    public int hashCode() {
        return java.lang.Long.hashCode(value);
    }

    @Override
    public java.lang.String toString() {
        return java.lang.Long.toString(value);
    }
}
