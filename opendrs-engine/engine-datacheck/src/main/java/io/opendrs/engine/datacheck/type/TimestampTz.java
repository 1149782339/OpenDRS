package io.opendrs.engine.datacheck.type;

/** UTC absolute instant. */
public record TimestampTz(long nanos) implements LogicalValue {
    @Override
    public LogicalType type() {
        return LogicalType.TIMESTAMPTZ;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof TimestampTz(long nanos1))) {
            return false;
        }
        return nanos == nanos1;
    }

    @Override
    public int hashCode() {
        return java.lang.Long.hashCode(nanos);
    }

    /** The absolute instant is already an epoch count; the zone adds nothing to the comparison. */
    @Override
    public void serialize(StringBuilder builder) {
        builder.append(nanos);
    }

    @Override
    public java.lang.String toString() {
        return java.lang.Long.toString(nanos);
    }
}
