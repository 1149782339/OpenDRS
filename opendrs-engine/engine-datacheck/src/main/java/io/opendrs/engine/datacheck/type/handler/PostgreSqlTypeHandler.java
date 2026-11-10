package io.opendrs.engine.datacheck.type.handler;

import io.opendrs.engine.datacheck.type.Binary;
import io.opendrs.engine.datacheck.type.Bit;
import io.opendrs.engine.datacheck.type.Bool;
import io.opendrs.engine.datacheck.type.Date;
import io.opendrs.engine.datacheck.type.Decimal;
import io.opendrs.engine.datacheck.type.Integer;
import io.opendrs.engine.datacheck.type.LogicalValue;
import io.opendrs.engine.datacheck.type.Long;
import io.opendrs.engine.datacheck.type.Null;
import io.opendrs.engine.datacheck.type.String;
import io.opendrs.engine.datacheck.type.Time;
import io.opendrs.engine.datacheck.type.Timestamp;
import io.opendrs.engine.datacheck.type.TimestampTz;

import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Reads PostgreSQL result-set rows into the same logical values the MySQL handler produces. */
public class PostgreSqlTypeHandler implements TypeHandler {

    private static final long NANOS_PER_DAY = 24L * 60 * 60 * 1_000_000_000L;

    @Override
    public List<LogicalValue> getValues(ResultSet resultSet, ResultSetMetaData metaData) {
        try {
            int columnCount = metaData.getColumnCount();
            List<LogicalValue> values = new ArrayList<>(columnCount);
            for (int i = 1; i <= columnCount; i++) {
                values.add(readColumn(resultSet, metaData, i));
            }
            return values;
        } catch (SQLException e) {
            throw new RuntimeException("failed to read PostgreSQL ResultSet values", e);
        }
    }

    private LogicalValue readColumn(ResultSet rs, ResultSetMetaData meta, int index)
            throws SQLException {
        java.lang.String typeName = meta.getColumnTypeName(index);
        if (typeName == null) {
            throw new SQLException("column " + index + " has null ColumnTypeName");
        }
        java.lang.String normalized = typeName.toLowerCase(Locale.ROOT).trim();
        return switch (normalized) {
            case "int2", "int4", "serial" -> readInteger(rs, index);
            case "int8", "bigserial" -> readLong(rs, index);
            case "numeric", "decimal" -> readDecimal(rs, index);
            case "bool" -> readBool(rs, index);
            case "bytea" -> readBinary(rs, index);
            case "bit", "varbit" -> readBit(rs, index);
            case "date" -> readDate(rs, index);
            case "time" -> readTime(rs, index);
            case "timestamp" -> readTimestamp(rs, index);
            case "timestamptz" -> readTimestampTz(rs, index);
            case "float4", "float8", "real", "double precision" -> Null.INSTANCE;
            // PostgreSQL intervals need their own parser; skipping keeps the check running.
            case "interval" -> Null.INSTANCE;
            case "varchar", "text", "bpchar", "char", "uuid", "json", "jsonb", "xml", "name",
                 "oid", "money", "timetz" -> readString(rs, index);
            default -> throw new SQLException(
                    "unsupported PostgreSQL ColumnTypeName: " + typeName + " (column " + index + ")");
        };
    }

    private LogicalValue readInteger(ResultSet rs, int index) throws SQLException {
        int value = rs.getInt(index);
        return rs.wasNull() ? Null.INSTANCE : new Integer(value);
    }

    private LogicalValue readLong(ResultSet rs, int index) throws SQLException {
        long value = rs.getLong(index);
        return rs.wasNull() ? Null.INSTANCE : new Long(value);
    }

    private LogicalValue readDecimal(ResultSet rs, int index) throws SQLException {
        java.math.BigDecimal value = rs.getBigDecimal(index);
        return value == null || rs.wasNull() ? Null.INSTANCE : new Decimal(value);
    }

    private LogicalValue readBool(ResultSet rs, int index) throws SQLException {
        boolean value = rs.getBoolean(index);
        return rs.wasNull() ? Null.INSTANCE : new Bool(value);
    }

    private LogicalValue readBinary(ResultSet rs, int index) throws SQLException {
        byte[] bytes = rs.getBytes(index);
        return bytes == null || rs.wasNull() ? Null.INSTANCE : new Binary(bytes);
    }

    /** PostgreSQL {@code bit}/{@code varbit} come back as {@code "1010"} strings. */
    private LogicalValue readBit(ResultSet rs, int index) throws SQLException {
        java.lang.String text = rs.getString(index);
        if (text == null || rs.wasNull()) {
            return Null.INSTANCE;
        }
        int padding = (8 - text.length() % 8) % 8;
        java.lang.String bits = "0".repeat(padding) + text;
        byte[] bytes = new byte[bits.length() / 8];
        for (int i = 0; i < bits.length(); i++) {
            if (bits.charAt(i) == '1') {
                bytes[i / 8] |= (byte) (1 << (7 - i % 8));
            }
        }
        return new Bit(bytes);
    }

    private LogicalValue readDate(ResultSet rs, int index) throws SQLException {
        LocalDate date = rs.getObject(index, LocalDate.class);
        if (date == null || rs.wasNull()) {
            return Null.INSTANCE;
        }
        return new Date(Math.toIntExact(date.toEpochDay()));
    }

    private LogicalValue readTime(ResultSet rs, int index) throws SQLException {
        LocalTime time = rs.getObject(index, LocalTime.class);
        if (time == null || rs.wasNull()) {
            return Null.INSTANCE;
        }
        return new Time(time.toNanoOfDay());
    }

    private LogicalValue readTimestamp(ResultSet rs, int index) throws SQLException {
        LocalDateTime dateTime = rs.getObject(index, LocalDateTime.class);
        if (dateTime == null || rs.wasNull()) {
            return Null.INSTANCE;
        }
        long nanos = dateTime.toLocalDate().toEpochDay() * NANOS_PER_DAY
                + dateTime.toLocalTime().toNanoOfDay();
        return new Timestamp(nanos);
    }

    private LogicalValue readTimestampTz(ResultSet rs, int index) throws SQLException {
        java.sql.Timestamp timestamp = rs.getTimestamp(index);
        if (timestamp == null || rs.wasNull()) {
            return Null.INSTANCE;
        }
        Instant instant = timestamp.toInstant();
        long nanos = Math.multiplyExact(instant.getEpochSecond(), 1_000_000_000L) + instant.getNano();
        return new TimestampTz(nanos);
    }

    private LogicalValue readString(ResultSet rs, int index) throws SQLException {
        java.lang.String value = rs.getString(index);
        return value == null || rs.wasNull() ? Null.INSTANCE : new String(value);
    }
}
