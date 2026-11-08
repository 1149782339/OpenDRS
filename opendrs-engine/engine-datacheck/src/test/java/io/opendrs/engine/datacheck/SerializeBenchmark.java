package io.opendrs.engine.datacheck;

import io.opendrs.engine.common.metadata.DatabaseType;
import io.opendrs.engine.common.util.Pair;
import io.opendrs.engine.datacheck.type.Binary;
import io.opendrs.engine.datacheck.type.Bit;
import io.opendrs.engine.datacheck.type.Bool;
import io.opendrs.engine.datacheck.type.Date;
import io.opendrs.engine.datacheck.type.Decimal;
import io.opendrs.engine.datacheck.type.Integer;
import io.opendrs.engine.datacheck.type.Interval;
import io.opendrs.engine.datacheck.type.LogicalValue;
import io.opendrs.engine.datacheck.type.Long;
import io.opendrs.engine.datacheck.type.Null;
import io.opendrs.engine.datacheck.type.String;
import io.opendrs.engine.datacheck.type.Time;
import io.opendrs.engine.datacheck.type.Timestamp;
import io.opendrs.engine.datacheck.type.TimestampTz;
import io.opendrs.engine.datacheck.type.serialize.PostgreSqlSerialize;

import java.lang.management.ManagementFactory;
import java.math.BigDecimal;
import java.util.HexFormat;

/**
 * Compares the current API — {@code Pair<String,String> serialize(...)}, then append both strings —
 * against the alternative where a value appends itself straight into a {@link StringBuilder}.
 *
 * <p>The second variant is a prototype of {@code LogicalValue#appendTo(StringBuilder)}: the same
 * text is produced, but without the two intermediate strings and the pair per column. Run with
 * {@code java -cp <classes> io.opendrs.engine.datacheck.SerializeBenchmark}. It is a plain
 * {@code main}, not a JUnit test: timings need warmup and a reused workload, not assertions.
 */
public final class SerializeBenchmark {

    private static final PostgreSqlSerialize SERIALIZER = new PostgreSqlSerialize();
    private static final HexFormat HEX = HexFormat.of();

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
        StringBuilder pairLeft = new StringBuilder(256);
        StringBuilder pairRight = new StringBuilder(256);
        StringBuilder appendLeft = new StringBuilder(256);
        StringBuilder appendRight = new StringBuilder(256);

        pairRow(row, pairLeft, pairRight);
        appendRow(row, appendLeft, appendRight);
        if (!pairLeft.toString().equals(appendLeft.toString())
                || !pairRight.toString().equals(appendRight.toString())) {
            throw new AssertionError("the two variants produce different text:\n  pair:   "
                    + pairLeft + "\n  append: " + appendLeft);
        }
        System.out.println("\n== " + label + " == " + row.length + " columns, reference row "
                + pairLeft.length() + " chars, text equality OK");

        for (int round = 0; round < WARMUP_ROUNDS; round++) {
            measure(true, row);
            measure(false, row);
        }

        double pairNanos = 0;
        double appendNanos = 0;
        double pairBytes = 0;
        double appendBytes = 0;
        System.out.printf("%-7s %14s %14s %14s %14s %10s %10s%n",
                "round", "pair ms", "pair MB", "append ms", "append MB", "speedup", "alloc x");
        for (int round = 1; round <= MEASURE_ROUNDS; round++) {
            Result pair = measure(true, row);
            Result append = measure(false, row);
            pairNanos += pair.nanos;
            appendNanos += append.nanos;
            pairBytes += pair.allocated;
            appendBytes += append.allocated;
            System.out.printf("%-7d %14.1f %14.1f %14.1f %14.1f %9.2fx %9.1fx%n",
                    round,
                    pair.nanos / 1e6, pair.allocated / 1e6,
                    append.nanos / 1e6, append.allocated / 1e6,
                    (double) pair.nanos / append.nanos,
                    (double) pair.allocated / append.allocated);
        }

        double columns = (double) ROWS * row.length * 2;
        System.out.printf("%nper column-side: pair %.1f ns / %.0f B, append %.1f ns / %.0f B%n",
                pairNanos / MEASURE_ROUNDS / columns,
                pairBytes / MEASURE_ROUNDS / columns,
                appendNanos / MEASURE_ROUNDS / columns,
                appendBytes / MEASURE_ROUNDS / columns);
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
                "type", "pair ns", "append ns", "speedup", "pair B", "append B", "alloc x");
        for (LogicalValue value : row) {
            long pairNanos = java.lang.Long.MAX_VALUE;
            long appendNanos = java.lang.Long.MAX_VALUE;
            long pairBytes = 0;
            long appendBytes = 0;
            for (int round = 0; round < 4; round++) {
                Result pair = measureValue(true, value, iterations);
                Result append = measureValue(false, value, iterations);
                if (round == 3) {
                    pairNanos = pair.nanos;
                    appendNanos = append.nanos;
                    pairBytes = pair.allocated;
                    appendBytes = append.allocated;
                }
            }
            System.out.printf("%-14s %10.1f %10.1f %9.2fx %10.1f %10.1f %9.1fx%n",
                    typeName(value),
                    (double) pairNanos / iterations,
                    (double) appendNanos / iterations,
                    (double) pairNanos / appendNanos,
                    (double) pairBytes / iterations,
                    (double) appendBytes / iterations,
                    appendBytes == 0 ? 0.0 : (double) pairBytes / appendBytes);
        }
    }

    private static Result measureValue(boolean pair, LogicalValue value, int iterations) {
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
            if (pair) {
                Pair<java.lang.String, java.lang.String> pairValue =
                        SERIALIZER.serialize(value, value, DatabaseType.MYSQL);
                left.append(pairValue.getFirst());
                right.append(pairValue.getSecond());
            } else {
                appendTo(value, left, DatabaseType.MYSQL);
                appendTo(value, right, DatabaseType.POSTGRESQL);
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

    private static java.lang.String typeName(LogicalValue value) {
        return value.type().toString();
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

    private static Result measure(boolean pair, LogicalValue[] row) {
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
            if (pair) {
                pairRow(row, left, right);
            } else {
                appendRow(row, left, right);
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

    private static void pairRow(LogicalValue[] row, StringBuilder left, StringBuilder right) {
        for (LogicalValue value : row) {
            Pair<java.lang.String, java.lang.String> pair =
                    SERIALIZER.serialize(value, value, DatabaseType.MYSQL);
            left.append(pair.getFirst());
            right.append(pair.getSecond());
        }
    }

    private static void appendRow(LogicalValue[] row, StringBuilder left, StringBuilder right) {
        for (LogicalValue value : row) {
            appendTo(value, left, DatabaseType.MYSQL);
            appendTo(value, right, DatabaseType.POSTGRESQL);
        }
    }

    /** Prototype of the per-value fast path: same text as toString, written into the target. */
    private static void appendTo(LogicalValue value, StringBuilder out, DatabaseType databaseType) {
        if (value == null) {
            out.append("null");
            return;
        }
        if (databaseType == DatabaseType.MYSQL && value instanceof Binary(byte[] bytes)) {
            if (bytes == null) {
                out.append("null");
                return;
            }
            int end = bytes.length;
            while (end > 0 && bytes[end - 1] == 0) {
                end--;
            }
            HEX.formatHex(out, bytes, 0, end);
            return;
        }
        appendTo(value, out);
    }

    private static void appendTo(LogicalValue value, StringBuilder out) {
        if (value instanceof Integer(int number)) {
            out.append(number);
        } else if (value instanceof Long(long number)) {
            out.append(number);
        } else if (value instanceof Bool(boolean flag)) {
            out.append(flag);
        } else if (value instanceof Decimal(BigDecimal number)) {
            out.append(number == null ? "null" : number.stripTrailingZeros().toPlainString());
        } else if (value instanceof String(java.lang.String text)) {
            out.append(text == null ? "null" : text);
        } else if (value instanceof Binary(byte[] bytes)) {
            if (bytes == null) {
                out.append("null");
            } else {
                HEX.formatHex(out, bytes);
            }
        } else if (value instanceof Bit(byte[] bytes)) {
            appendBit(out, bytes);
        } else if (value instanceof Date(int epochDay)) {
            out.append(epochDay);
        } else if (value instanceof Time(long nanosOfDay)) {
            out.append(nanosOfDay);
        } else if (value instanceof Timestamp(long nanos)) {
            out.append(nanos);
        } else if (value instanceof TimestampTz(long nanos)) {
            out.append(nanos);
        } else if (value instanceof Interval(int years, int months, int days, long nanos)) {
            appendInterval(out, years, months, days, nanos);
        } else if (value instanceof Null) {
            out.append("null");
        } else {
            out.append(value);
        }
    }

    private static void appendBit(StringBuilder out, byte[] bytes) {
        if (bytes == null) {
            out.append("null");
            return;
        }
        int firstOne = -1;
        for (int i = 0; i < bytes.length; i++) {
            if (bytes[i] != 0) {
                firstOne = i;
                break;
            }
        }
        if (firstOne < 0) {
            out.append('0');
            return;
        }
        boolean started = false;
        for (int i = firstOne; i < bytes.length; i++) {
            for (int shift = 7; shift >= 0; shift--) {
                int bit = (bytes[i] >> shift) & 1;
                if (bit == 1 || started) {
                    out.append(bit);
                    started = true;
                }
            }
        }
        if (!started) {
            out.append('0');
        }
    }

    private static void appendInterval(
            StringBuilder out, int years, int months, int days, long nanos) {
        int start = out.length();
        out.append('P');
        if (years != 0) {
            out.append(years).append('Y');
        }
        if (months != 0) {
            out.append(months).append('M');
        }
        if (days != 0) {
            out.append(days).append('D');
        }
        if (nanos != 0) {
            out.append('T')
                    .append(BigDecimal.valueOf(nanos, 9).stripTrailingZeros().toPlainString())
                    .append('S');
        }
        if (out.length() == start + 1) {
            out.append("0D");
        }
    }

    private record Result(long nanos, long allocated) {}

    private SerializeBenchmark() {}
}
