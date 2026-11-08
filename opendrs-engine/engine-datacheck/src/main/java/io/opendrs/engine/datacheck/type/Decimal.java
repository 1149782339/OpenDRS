package io.opendrs.engine.datacheck.type;

import java.math.BigDecimal;
import java.util.Objects;

public record Decimal(BigDecimal value) implements LogicalValue {
    @Override
    public LogicalType type() {
        return LogicalType.DECIMAL;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Decimal(BigDecimal other))) {
            return false;
        }
        return value == null ? other == null : value.compareTo(other) == 0;
    }

    @Override
    public int hashCode() {
        return value == null ? 0 : Objects.hash(value.stripTrailingZeros());
    }

    /** Plain decimal without trailing zeros, so the scale a database reports does not show up. */
    @Override
    public void serialize(StringBuilder builder) {
        builder.append(value == null ? "null" : value.stripTrailingZeros().toPlainString());
    }

    @Override
    public java.lang.String toString() {
        StringBuilder builder = new StringBuilder();
        serialize(builder);
        return builder.toString();
    }
}
