package io.opendrs.engine.datacheck.type;

import java.util.Arrays;
import java.util.Objects;

public record Binary(byte[] bytes) implements LogicalValue {

    private static final char[] HEX = "0123456789abcdef".toCharArray();

    public Binary {
        bytes = bytes == null ? null : bytes.clone();
    }

    @Override
    public byte[] bytes() {
        return bytes == null ? null : bytes.clone();
    }

    @Override
    public LogicalType type() {
        return LogicalType.BINARY;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Binary other)) {
            return false;
        }
        return Arrays.equals(bytes, other.bytes);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(bytes);
    }

    /** Lowercase hex, the textual form of a byte string on both sides. */
    @Override
    public void serialize(StringBuilder builder) {
        if (bytes == null) {
            builder.append("null");
            return;
        }
        for (byte b : bytes) {
            builder.append(HEX[(b >> 4) & 0x0F]).append(HEX[b & 0x0F]);
        }
    }

    @Override
    public java.lang.String toString() {
        StringBuilder builder = new StringBuilder(bytes == null ? 4 : bytes.length * 2);
        serialize(builder);
        return builder.toString();
    }
}
