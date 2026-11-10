package io.opendrs.engine.datacheck.type;

/** Days since Unix epoch (UTC), no time-of-day. */
public record Date(int epochDay) implements LogicalValue {
    @Override
    public LogicalType type() {
        return LogicalType.DATE;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Date(int day))) {
            return false;
        }
        return epochDay == day;
    }

    @Override
    public int hashCode() {
        return java.lang.Integer.hashCode(epochDay);
    }

    /** Days since the Unix epoch are the value itself; no date formatting is needed to compare. */
    @Override
    public void serialize(StringBuilder builder) {
        builder.append(epochDay);
    }

    @Override
    public java.lang.String toString() {
        return java.lang.Integer.toString(epochDay);
    }
}
