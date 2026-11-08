package io.opendrs.engine.datacheck.type;

import java.util.Objects;

public record String(java.lang.String value) implements LogicalValue {
    @Override
    public LogicalType type() {
        return LogicalType.STRING;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof String(java.lang.String value1))) {
            return false;
        }
        return Objects.equals(value, value1);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(value);
    }

    @Override
    public java.lang.String toString() {
        return value == null ? "null" : value;
    }
}
