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
    public java.lang.String toString() {
        if (bytes == null) {
            return "null";
        }
        java.lang.StringBuilder bits = new java.lang.StringBuilder(bytes.length * 8);
        for (byte b : bytes) {
            for (int shift = 7; shift >= 0; shift--) {
                bits.append((b >> shift) & 1);
            }
        }
        int firstOne = 0;
        while (firstOne < bits.length() - 1 && bits.charAt(firstOne) == '0') {
            firstOne++;
        }
        return bits.substring(firstOne);
    }
}
