package io.opendrs.engine.datacheck.type;

import java.util.Arrays;

public record Bit(byte[] bytes) implements LogicalValue {
    public Bit {
        bytes = bytes == null ? null : bytes.clone();
    }

    @Override
    public byte[] bytes() {
        return bytes == null ? null : bytes.clone();
    }

    @Override
    public LogicalType type() {
        return LogicalType.BIT;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Bit other)) {
            return false;
        }
        return Arrays.equals(bytes, other.bytes);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(bytes);
    }

    /** Bit string with the leading zeros MySQL does not pad with dropped. */
    @Override
    public void serialize(StringBuilder builder) {
        if (bytes == null) {
            builder.append("null");
            return;
        }
        if (bytes.length == 0) {
            return;
        }
        boolean started = false;
        for (byte b : bytes) {
            for (int shift = 7; shift >= 0; shift--) {
                int bit = (b >> shift) & 1;
                if (bit == 1 || started) {
                    builder.append(bit);
                    started = true;
                }
            }
        }
        if (!started) {
            builder.append('0');
        }
    }

    @Override
    public java.lang.String toString() {
        StringBuilder builder = new StringBuilder(bytes == null ? 4 : bytes.length);
        serialize(builder);
        return builder.toString();
    }
}
