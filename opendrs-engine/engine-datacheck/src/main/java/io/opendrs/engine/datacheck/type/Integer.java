package io.opendrs.engine.datacheck.type;

public record Integer(int value) implements LogicalValue {
    @Override
    public LogicalType type() {
        return LogicalType.INTEGER;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Integer(int other))) {
            return false;
        }
        return value == other;
    }

    @Override
    public int hashCode() {
        return java.lang.Integer.hashCode(value);
    }

    @Override
    public void serialize(StringBuilder builder) {
        builder.append(value);
    }

    @Override
    public java.lang.String toString() {
        return java.lang.Integer.toString(value);
    }
}
