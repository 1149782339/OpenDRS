package io.opendrs.engine.datacheck.type;

/** Nanoseconds since midnight, no date / zone. */
public record Time(long nanosOfDay) implements LogicalValue {
    @Override
    public LogicalType type() {
        return LogicalType.TIME;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Time(long ofDay))) {
            return false;
        }
        return nanosOfDay == ofDay;
    }

    @Override
    public int hashCode() {
        return java.lang.Long.hashCode(nanosOfDay);
    }

    /** Nanos since midnight are the value itself; MySQL's out-of-day range needs no special case. */
    @Override
    public void serialize(StringBuilder builder) {
        builder.append(nanosOfDay);
    }

    @Override
    public java.lang.String toString() {
        return java.lang.Long.toString(nanosOfDay);
    }
}
