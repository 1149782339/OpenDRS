package io.opendrs.engine.datacheck.type;

public record Bool(boolean value) implements LogicalValue {
    @Override
    public LogicalType type() {
        return LogicalType.BOOL;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Bool(boolean other))) {
            return false;
        }
        return value == other;
    }

    @Override
    public int hashCode() {
        return java.lang.Boolean.hashCode(value);
    }

    @Override
    public void serialize(StringBuilder builder) {
        builder.append(value);
    }

    @Override
    public java.lang.String toString() {
        return java.lang.Boolean.toString(value);
    }
}
