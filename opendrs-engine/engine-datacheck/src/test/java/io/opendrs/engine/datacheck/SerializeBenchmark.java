package io.opendrs.engine.datacheck;

import io.opendrs.engine.datacheck.type.Binary;
import io.opendrs.engine.datacheck.type.Bool;
import io.opendrs.engine.datacheck.type.Date;
import io.opendrs.engine.datacheck.type.Decimal;
import io.opendrs.engine.datacheck.type.Integer;
import io.opendrs.engine.datacheck.type.LogicalValue;
import io.opendrs.engine.datacheck.type.Long;
import io.opendrs.engine.datacheck.type.String;
import io.opendrs.engine.datacheck.type.Timestamp;
import io.opendrs.engine.datacheck.type.TimestampTz;

import java.lang.management.ManagementFactory;
import java.math.BigDecimal;

/**
 * Compares appending a value through {@link Object#toString()} (which allocates a String per
 * column) against {@link LogicalValue#serialize(StringBuilder)} (which writes straight into the
 * caller's buffer).
 *
 * <p>Run with {@code java -cp <classes> io.opendrs.engine.datacheck.SerializeBenchmark}. It is a
 * plain {@code main}, not a JUnit test: timings need warmup and a reused workload.
 */
public final class SerializeBenchmark {

    private static final int ROWS = 500_000;
    private static final int WARMUP_ROUNDS = 3;
    private static final int MEASURE_ROUNDS = 5;

    public static void main(java.lang.String[] args) {
        LogicalValue[] row = row();
        runRow("all columns", row);
        runRow("without BINARY", withoutBinary(row));
        perType(row);
    }

    private static void runRow(java.lang.String label, LogicalValue[] row) {
        StringBuilder toStringLeft = new StringBuilder(256);
        StringBuilder toStringRight = new StringBuilder(256);
        StringBuilder serializeLeft = new StringBuilder(256);
        StringBuilder serializeRight = new StringBuilder(256);

        toStringRow(row, toStringLeft, toStringRight);
        serializeRow(row, serializeLeft, serializeRight);
        if (!toStringLeft.toString().equals(serializeLeft.toString())
                || !toStringRight.toString().equals(serializeRight.toString())) {
            throw new AssertionError("the two variants produce different text:\n  toString:  "
                    + toStringLeft + "\n  serialize: " + serializeLeft);
        }
        System.out.println("\n== " + label + " == " + row.length + " columns, reference row "
                + toStringLeft.length() + " chars, text equality OK");

        for (int round = 0; round < WARMUP_ROUNDS; round++) {
            measure(true, row);
            measure(false, row);
        }

        double toStringNanos = 0;
        double serializeNanos = 0;
        double toStringBytes = 0;
        double serializeBytes = 0;
        System.out.printf("%-7s %14s %14s %14s %14s %10s %10s%n",
                "round", "toString ms", "toString MB", "serialize ms", "serialize MB",
                "speedup", "alloc x");
        for (int round = 1; round <= MEASURE_ROUNDS; round++) {
            Result toStringResult = measure(true, row);
            Result serializeResult = measure(false, row);
            toStringNanos += toStringResult.nanos;
            serializeNanos += serializeResult.nanos;
            toStringBytes += toStringResult.allocated;
            serializeBytes += serializeResult.allocated;
            System.out.printf("%-7d %14.1f %14.1f %14.1f %14.1f %9.2fx %9.1fx%n",
                    round,
                    toStringResult.nanos / 1e6, toStringResult.allocated / 1e6,
                    serializeResult.nanos / 1e6, serializeResult.allocated / 1e6,
                    (double) toStringResult.nanos / serializeResult.nanos,
                    (double) toStringResult.allocated / serializeResult.allocated);
        }

        double columns = (double) ROWS * row.length * 2;
        System.out.printf("%nper column-side: toString %.1f ns / %.0f B, serialize %.1f ns / %.0f B%n",
                toStringNanos / MEASURE_ROUNDS / columns,
                toStringBytes / MEASURE_ROUNDS / columns,
                serializeNanos / MEASURE_ROUNDS / columns,
                serializeBytes / MEASURE_ROUNDS / columns);
    }

    private static LogicalValue[] withoutBinary(LogicalValue[] row) {
        java.util.List<LogicalValue> values = new java.util.ArrayList<>(row.length);
        for (LogicalValue value : row) {
            if (!(value instanceof Binary)) {
                values.add(value);
            }
        }
        return values.toArray(new LogicalValue[0]);
    }

    /** The row aggregate hides which types actually pay for the strings; break it down. */
    private static void perType(LogicalValue[] row) {
        int iterations = 3_000_000;
        System.out.printf("%nper type, %d iterations, both sides per iteration:%n", iterations);
        System.out.printf("%-14s %10s %10s %10s %10s %10s %10s%n",
                "type", "toString ns", "serialize ns", "speedup",
                "toString B", "serialize B", "alloc x");
        for (LogicalValue value : row) {
            Result toStringResult = null;
            Result serializeResult = null;
            for (int round = 0; round < 4; round++) {
                toStringResult = measureValue(true, value, iterations);
                serializeResult = measureValue(false, value, iterations);
            }
            System.out.printf("%-14s %10.1f %10.1f %9.2fx %10.1f %10.1f %9.1fx%n",
                    value.type(),
                    (double) toStringResult.nanos / iterations,
                    (double) serializeResult.nanos / iterations,
                    (double) toStringResult.nanos / serializeResult.nanos,
                    (double) toStringResult.allocated / iterations,
                    (double) serializeResult.allocated / iterations,
                    serializeResult.allocated == 0
                            ? 0.0
                            : (double) toStringResult.allocated / serializeResult.allocated);
        }
    }

    private static Result measureValue(boolean toStringPath, LogicalValue value, int iterations) {
        StringBuilder left = new StringBuilder(64);
        StringBuilder right = new StringBuilder(64);
        com.sun.management.ThreadMXBean mx =
                (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        long sink = 0;
        long start = System.nanoTime();
        long allocatedBefore = mx.getCurrentThreadAllocatedBytes();
        for (int i = 0; i < iterations; i++) {
            left.setLength(0);
            right.setLength(0);
            if (toStringPath) {
                left.append(value.toString());
                right.append(value.toString());
            } else {
                value.serialize(left);
                value.serialize(right);
            }
            sink += left.length() + right.length();
        }
        long nanos = System.nanoTime() - start;
        long allocated = mx.getCurrentThreadAllocatedBytes() - allocatedBefore;
        if (sink < 0) {
            throw new AssertionError();
        }
        return new Result(nanos, allocated);
    }

    private static LogicalValue[] row() {
        return new LogicalValue[] {
            new Integer(123_456),
            new Long(9_000_000_000L),
            new Decimal(new BigDecimal("12345.6789")),
            new String("hello world"),
            new Timestamp(1_700_000_000_123_456_789L),
            new TimestampTz(1_700_000_000_123_456_789L),
            new Binary(new byte[] {1, 2, 3, 4, 5, 6, 7, 8, 0, 0}),
            new Date(19_000),
            new Bool(true),
        };
    }

    private static Result measure(boolean toStringPath, LogicalValue[] row) {
        StringBuilder left = new StringBuilder(256);
        StringBuilder right = new StringBuilder(256);
        com.sun.management.ThreadMXBean mx =
                (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        long sink = 0;
        long start = System.nanoTime();
        long allocatedBefore = mx.getCurrentThreadAllocatedBytes();
        for (int r = 0; r < ROWS; r++) {
            left.setLength(0);
            right.setLength(0);
            if (toStringPath) {
                toStringRow(row, left, right);
            } else {
                serializeRow(row, left, right);
            }
            sink += left.length() + right.length();
        }
        long nanos = System.nanoTime() - start;
        long allocated = mx.getCurrentThreadAllocatedBytes() - allocatedBefore;
        if (sink < 0) {
            throw new AssertionError();
        }
        return new Result(nanos, allocated);
    }

    private static void toStringRow(LogicalValue[] row, StringBuilder left, StringBuilder right) {
        for (LogicalValue value : row) {
            left.append(value.toString());
            right.append(value.toString());
        }
    }

    private static void serializeRow(LogicalValue[] row, StringBuilder left, StringBuilder right) {
        for (LogicalValue value : row) {
            value.serialize(left);
            value.serialize(right);
        }
    }

    private record Result(long nanos, long allocated) {}

    private SerializeBenchmark() {}
}
