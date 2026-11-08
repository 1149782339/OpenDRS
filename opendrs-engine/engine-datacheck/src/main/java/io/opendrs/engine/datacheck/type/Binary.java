package io.opendrs.engine.datacheck.type;

import java.util.Arrays;
import java.util.Objects;

public record Binary(byte[] bytes) implements LogicalValue {

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
    public java.lang.String toString() {
        return bytes == null ? "null" : java.util.HexFormat.of().formatHex(bytes);
    }
}
