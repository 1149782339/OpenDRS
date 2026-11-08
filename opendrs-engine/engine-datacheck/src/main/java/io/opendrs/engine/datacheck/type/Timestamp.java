package io.opendrs.engine.datacheck.type;

public record Timestamp(long nanos) implements LogicalValue {
    @Override
    public LogicalType type() {
        return LogicalType.TIMESTAMP;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Timestamp(long nanos1))) {
            return false;
        }
        return nanos == nanos1;
    }

    @Override
    public int hashCode() {
        return java.lang.Long.hashCode(nanos);
    }

    /** Nanos since the Unix epoch are the value itself; no date formatting is needed to compare. */
    @Override
    public java.lang.String toString() {
        return java.lang.Long.toString(nanos);
    }
}
