package io.opendrs.engine.datacheck.incremental;

import io.opendrs.engine.datacheck.InconsistentRowStore;
import io.opendrs.engine.replay.event.DataChangeEvent;
import io.opendrs.engine.replay.event.Operation;
import io.opendrs.engine.replay.relation.FieldsMetaData;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Date;
import java.sql.Time;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Encodes event PK values to the same strings {@link java.sql.ResultSet#getString(int)} typically
 * returns, then joins them with {@link InconsistentRowStore#encode(List)} so store keys match the
 * full check.
 */
public final class PrimaryKeyEncoder {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ISO_LOCAL_DATE;
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final DateTimeFormatter DATE_TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter DATE_TIME_FRACTION =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSSSSSSSS");

    private PrimaryKeyEncoder() {
    }

    public static String encode(List<?> values) {
        List<String> parts = new ArrayList<>(values == null ? 0 : values.size());
        if (values != null) {
            for (Object value : values) {
                parts.add(toJdbcString(value));
            }
        }
        return InconsistentRowStore.encode(parts);
    }

    /**
     * Bind values in primary-key column order (source names). DELETE uses {@code before};
     * other ops use {@code after}, falling back to {@code before}.
     */
    public static List<Object> bindValues(DataChangeEvent event) {
        FieldsMetaData meta = event.getFieldsMetaData();
        if (meta == null || meta.getPrimaryKeyFieldNames().isEmpty()) {
            return List.of();
        }
        Map<String, Object> values = event.getOperation() == Operation.DELETE
                ? event.getBeforeValues()
                : event.getAfterValues();
        if (values == null || values.isEmpty()) {
            values = event.getBeforeValues();
        }
        if (values == null) {
            values = Map.of();
        }
        List<Object> bind = new ArrayList<>(meta.getPrimaryKeyFieldNames().size());
        for (String name : meta.getPrimaryKeyFieldNames()) {
            bind.add(values.get(name));
        }
        return bind;
    }

    public static String encode(DataChangeEvent event) {
        return encode(bindValues(event));
    }

    /**
     * Best-effort {@code ResultSet.getString} rendering for common Debezium / JDBC types.
     * Boolean becomes {@code 1}/{@code 0} (MySQL TINYINT(1) / BIT style). Binary uses ISO-8859-1
     * so it matches typical {@code getString} on {@code BINARY}/{@code VARBINARY} in Connector/J
     * and H2. Timestamps drop trailing fractional zeros.
     */
    public static String toJdbcString(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof String text) {
            return text;
        }
        if (value instanceof Boolean bool) {
            return bool ? "1" : "0";
        }
        if (value instanceof Byte || value instanceof Short || value instanceof Integer
                || value instanceof Long) {
            return String.valueOf(value);
        }
        if (value instanceof Float || value instanceof Double) {
            return new BigDecimal(value.toString()).stripTrailingZeros().toPlainString();
        }
        if (value instanceof BigDecimal decimal) {
            return decimal.toPlainString();
        }
        if (value instanceof byte[] bytes) {
            return new String(bytes, StandardCharsets.ISO_8859_1);
        }
        if (value instanceof Date date) {
            return date.toLocalDate().format(DATE);
        }
        if (value instanceof Time time) {
            return formatTime(time.toLocalTime());
        }
        if (value instanceof Timestamp timestamp) {
            return formatDateTime(timestamp.toLocalDateTime());
        }
        if (value instanceof LocalDate date) {
            return date.format(DATE);
        }
        if (value instanceof LocalTime time) {
            return formatTime(time);
        }
        if (value instanceof LocalDateTime dateTime) {
            return formatDateTime(dateTime);
        }
        if (value instanceof OffsetDateTime dateTime) {
            return formatDateTime(dateTime.toLocalDateTime());
        }
        if (value instanceof Instant instant) {
            return formatDateTime(LocalDateTime.ofInstant(instant, ZoneOffset.UTC));
        }
        if (value instanceof java.util.Date date) {
            return formatDateTime(LocalDateTime.ofInstant(date.toInstant(), ZoneOffset.UTC));
        }
        if (value instanceof Character character) {
            return String.valueOf(character);
        }
        return String.valueOf(value);
    }

    private static String formatTime(LocalTime time) {
        if (time.getNano() == 0) {
            return time.format(TIME);
        }
        String nanos = String.format("%09d", time.getNano()).replaceFirst("0+$", "");
        return time.withNano(0).format(TIME) + "." + nanos;
    }

    private static String formatDateTime(LocalDateTime dateTime) {
        if (dateTime.getNano() == 0) {
            return dateTime.format(DATE_TIME);
        }
        String formatted = dateTime.format(DATE_TIME_FRACTION);
        return formatted.replaceFirst("0+$", "");
    }
}
