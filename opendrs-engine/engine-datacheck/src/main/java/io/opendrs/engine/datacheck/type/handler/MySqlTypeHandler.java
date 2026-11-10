package io.opendrs.engine.datacheck.type.handler;

import io.opendrs.engine.datacheck.type.Binary;
import io.opendrs.engine.datacheck.type.Bit;
import io.opendrs.engine.datacheck.type.Bool;
import io.opendrs.engine.datacheck.type.Date;
import io.opendrs.engine.datacheck.type.Decimal;
import io.opendrs.engine.datacheck.type.Integer;
import io.opendrs.engine.datacheck.type.Long;
import io.opendrs.engine.datacheck.type.Null;
import io.opendrs.engine.datacheck.type.LogicalValue;
import io.opendrs.engine.datacheck.type.String;
import io.opendrs.engine.datacheck.type.Time;
import io.opendrs.engine.datacheck.type.Timestamp;
import io.opendrs.engine.datacheck.type.TimestampTz;

import java.math.BigDecimal;
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

public class MySqlTypeHandler implements TypeHandler {
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
            throw new RuntimeException("failed to read MySQL ResultSet values", e);
        }
    }

    private LogicalValue readColumn(ResultSet rs, ResultSetMetaData meta, int index)
            throws SQLException {
        java.lang.String typeName = meta.getColumnTypeName(index);
        if (typeName == null) {
            throw new SQLException("column " + index + " has null ColumnTypeName");
        }
        java.lang.String normalized = typeName.toUpperCase(Locale.ROOT);
        // strip display width / unsigned, e.g. BIGINT UNSIGNED, INT(11)
        int paren = normalized.indexOf('(');
        if (paren >= 0) {
            normalized = normalized.substring(0, paren).trim();
        }
        normalized = normalized.replace(" UNSIGNED", "").replace(" SIGNED", "").trim();

        return switch (normalized) {
            case "DATETIME" -> readDateTime(rs, index);
            case "TIMESTAMP" -> readTimestampTz(rs, index);
            case "DATE" -> readDate(rs, index);
            case "TIME" -> readTime(rs, index);
            case "DECIMAL", "NUMERIC", "YEAR" -> readDecimal(rs, index);
            case "BOOLEAN", "BOOL" -> readBool(rs, index);
            case "BIT" -> readBit(rs, index);
            case "FLOAT", "DOUBLE", "REAL" -> Null.INSTANCE;
            case "TINYINT", "SMALLINT", "MEDIUMINT", "INT", "INTEGER" -> readInteger(rs, index);
            case "BIGINT" -> readLong(rs, index);
            case "BINARY", "VARBINARY", "TINYBLOB", "BLOB", "MEDIUMBLOB", "LONGBLOB" ->
                    readBinary(rs, index);
            case "CHAR", "VARCHAR", "TINYTEXT", "TEXT", "MEDIUMTEXT", "LONGTEXT",
                 "ENUM", "SET", "JSON" -> readString(rs, index);
            default -> throw new SQLException(
                    "unsupported MySQL ColumnTypeName: " + typeName + " (column " + index + ")");
        };
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

    private LogicalValue readDateTime(ResultSet rs, int index) throws SQLException {
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




    private LogicalValue readBit(ResultSet rs, int index) throws SQLException {
        byte[] bytes = rs.getBytes(index);
        if (bytes == null || rs.wasNull()) {
            return Null.INSTANCE;
        }
        return new Bit(bytes);
    }
    private LogicalValue readBool(ResultSet rs, int index) throws SQLException {
        boolean value = rs.getBoolean(index);
        if (rs.wasNull()) {
            return Null.INSTANCE;
        }
        return new Bool(value);
    }
    private LogicalValue readInteger(ResultSet rs, int index) throws SQLException {
        int value = rs.getInt(index);
        if (rs.wasNull()) {
            return Null.INSTANCE;
        }
        return new Integer(value);
    }

    private LogicalValue readLong(ResultSet rs, int index) throws SQLException {
        long value = rs.getLong(index);
        if (rs.wasNull()) {
            return Null.INSTANCE;
        }
        return new Long(value);
    }
    private LogicalValue readDecimal(ResultSet rs, int index) throws SQLException {
        BigDecimal value = rs.getBigDecimal(index);
        if (value == null || rs.wasNull()) {
            return Null.INSTANCE;
        }
        return new Decimal(value);
    }

    private LogicalValue readBinary(ResultSet rs, int index) throws SQLException {
        byte[] bytes = rs.getBytes(index);
        if (bytes == null || rs.wasNull()) {
            return Null.INSTANCE;
        }
        return new Binary(bytes);
    }

    private LogicalValue readString(ResultSet rs, int index) throws SQLException {
        java.lang.String value = rs.getString(index);
        if (value == null || rs.wasNull()) {
            return Null.INSTANCE;
        }
        return new String(value);
    }
}






