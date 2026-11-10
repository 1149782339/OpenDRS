package io.opendrs.engine.datacheck.type;

public record Interval(int years, int months, int days, long nanos) implements LogicalValue {
    @Override
    public LogicalType type() {
        return LogicalType.INTERVAL;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Interval(int years1, int months1, int days1, long nanos1))) {
            return false;
        }
        return years == years1
                && months == months1
                && days == days1
                && nanos == nanos1;
    }

    @Override
    public int hashCode() {
        int result = java.lang.Integer.hashCode(years);
        result = 31 * result + java.lang.Integer.hashCode(months);
        result = 31 * result + java.lang.Integer.hashCode(days);
        result = 31 * result + java.lang.Long.hashCode(nanos);
        return result;
    }

    /** ISO-8601 duration, e.g. {@code P1Y2M3DT4.5S}, built from the fields as stored. */
    @Override
    public void serialize(StringBuilder builder) {
        int start = builder.length();
        builder.append('P');
        if (years != 0) {
            builder.append(years).append('Y');
        }
        if (months != 0) {
            builder.append(months).append('M');
        }
        if (days != 0) {
            builder.append(days).append('D');
        }
        if (nanos != 0) {
            builder.append('T')
                    .append(java.math.BigDecimal.valueOf(nanos, 9).stripTrailingZeros().toPlainString())
                    .append('S');
        }
        if (builder.length() == start + 1) {
            builder.append("0D");
        }
    }

    @Override
    public java.lang.String toString() {
        StringBuilder builder = new StringBuilder();
        serialize(builder);
        return builder.toString();
    }
}
