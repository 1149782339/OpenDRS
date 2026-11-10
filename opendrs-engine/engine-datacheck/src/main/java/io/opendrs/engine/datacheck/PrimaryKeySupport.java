package io.opendrs.engine.datacheck;

import io.opendrs.engine.common.metadata.ColumnDefinition;
import io.opendrs.engine.common.metadata.TableDefinition;
import io.opendrs.engine.common.metadata.TableId;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Data-check primary keys may only be integer or string types. Encoding is shared by the full
 * JDBC scan and incremental Debezium values so both sides produce identical PK strings.
 *
 * <p>Integers are a canonical decimal string (no leading zeros, no scientific notation).
 * {@code CHAR}/{@code NCHAR} values have trailing spaces stripped; {@code VARCHAR}/{@code TEXT}
 * are passed through unchanged. Null primary-key values are rejected.
 */
public final class PrimaryKeySupport {

    public enum Kind {
        INTEGER,
        CHAR,
        VARCHAR
    }

    private PrimaryKeySupport() {
    }

    /**
     * Validates every primary-key column of {@code definition}. Throws
     * {@link UnsupportedPrimaryKeyException} naming {@code table} and the offending column.
     */
    public static void validate(TableId table, TableDefinition definition) {
        Objects.requireNonNull(table, "table");
        if (definition == null) {
            throw new UnsupportedPrimaryKeyException(
                    table, null, null, "table not found: " + table);
        }
        List<String> pkNames = definition.getPrimaryKeyColumNames();
        if (pkNames == null || pkNames.isEmpty()) {
            throw new UnsupportedPrimaryKeyException(
                    table, null, null, "table " + table + " has no primary key");
        }
        List<ColumnDefinition> columns = definition.getColumnDefinitions();
        for (String name : pkNames) {
            ColumnDefinition column = column(columns, name);
            if (column == null) {
                throw new UnsupportedPrimaryKeyException(
                        table, name, null,
                        "primary key column " + name + " not found in metadata of " + table);
            }
            kindOf(table, column);
        }
    }

    public static List<ColumnDefinition> primaryKeyColumns(TableDefinition definition) {
        List<String> names = definition.getPrimaryKeyColumNames();
        List<ColumnDefinition> columns = definition.getColumnDefinitions();
        List<ColumnDefinition> pk = new ArrayList<>(names.size());
        for (String name : names) {
            ColumnDefinition column = column(columns, name);
            if (column == null) {
                throw new IllegalStateException("primary key column not found in metadata: " + name);
            }
            pk.add(column);
        }
        return pk;
    }

    public static Kind kindOf(TableId table, ColumnDefinition column) {
        Kind kind = detectKind(column);
        if (kind != null) {
            return kind;
        }
        String type = displayType(column);
        throw new UnsupportedPrimaryKeyException(
                table,
                column.getName(),
                type,
                "Unsupported primary key type on table " + table
                        + " (column " + column.getName() + "): " + type
                        + ". Data check primary keys must be integer or string types.");
    }

    public static Kind kindOf(ColumnDefinition column) {
        return kindOf(null, column);
    }

    /** Detects the kind without throwing; {@code null} means unsupported. */
    public static Kind detectKind(ColumnDefinition column) {
        if (column == null) {
            return null;
        }
        int jdbcType = column.getJdbcType();
        String typeName = normalizedTypeName(column);
        if (isBoolean(jdbcType, typeName)
                || isDecimalOrFloat(jdbcType, typeName)
                || isDateTime(jdbcType, typeName)
                || isBinary(jdbcType, typeName)) {
            return null;
        }
        if (isInteger(jdbcType, typeName)) {
            return Kind.INTEGER;
        }
        if (isCharFixed(jdbcType, typeName)) {
            return Kind.CHAR;
        }
        if (isVarcharOrText(jdbcType, typeName)) {
            return Kind.VARCHAR;
        }
        return null;
    }

    public static String encode(TableId table, List<ColumnDefinition> columns, List<?> values) {
        if (columns == null || values == null || columns.size() != values.size()) {
            throw new IllegalArgumentException("primary key columns and values must have the same size");
        }
        List<String> parts = new ArrayList<>(columns.size());
        for (int i = 0; i < columns.size(); i++) {
            parts.add(encodeColumn(table, columns.get(i), values.get(i)));
        }
        return InconsistentRowStore.encode(parts);
    }

    public static String encodeColumn(TableId table, ColumnDefinition column, Object value) {
        if (value == null) {
            throw new IllegalArgumentException(
                    "null primary key value is not allowed"
                            + (table == null ? "" : " on table " + table)
                            + (column == null || column.getName() == null ? "" : " (column " + column.getName() + ")"));
        }
        Kind kind = kindOf(table, column);
        return switch (kind) {
            case INTEGER -> encodeInteger(value);
            case CHAR -> rtrim(asString(value));
            case VARCHAR -> asString(value);
        };
    }

    /**
     * JDBC bind value for an incremental {@code IN} query: a numeric type for integer keys,
     * a (possibly rtrimmed) string for character keys.
     */
    public static Object bindValue(TableId table, ColumnDefinition column, Object value) {
        if (value == null) {
            throw new IllegalArgumentException(
                    "null primary key value is not allowed"
                            + (table == null ? "" : " on table " + table)
                            + (column == null || column.getName() == null ? "" : " (column " + column.getName() + ")"));
        }
        Kind kind = kindOf(table, column);
        return switch (kind) {
            case INTEGER -> bindInteger(value);
            case CHAR -> rtrim(asString(value));
            case VARCHAR -> asString(value);
        };
    }

    public static List<Object> bindValues(TableId table, List<ColumnDefinition> columns, List<?> values) {
        if (columns == null || values == null || columns.size() != values.size()) {
            throw new IllegalArgumentException("primary key columns and values must have the same size");
        }
        List<Object> bind = new ArrayList<>(columns.size());
        for (int i = 0; i < columns.size(); i++) {
            bind.add(bindValue(table, columns.get(i), values.get(i)));
        }
        return bind;
    }

    public static String encodeInteger(Object value) {
        return toBigInteger(value).toString();
    }

    static BigInteger toBigInteger(Object value) {
        if (value instanceof BigInteger number) {
            return number;
        }
        if (value instanceof BigDecimal decimal) {
            try {
                return decimal.stripTrailingZeros().toBigIntegerExact();
            } catch (ArithmeticException ex) {
                throw new IllegalArgumentException(
                        "integer primary key has a fractional value: " + decimal.toPlainString(), ex);
            }
        }
        if (value instanceof Byte || value instanceof Short || value instanceof Integer
                || value instanceof Long) {
            return BigInteger.valueOf(((Number) value).longValue());
        }
        if (value instanceof Number number) {
            // e.g. AtomicInteger; reject floats.
            if (value instanceof Float || value instanceof Double) {
                throw new IllegalArgumentException(
                        "integer primary key cannot be a floating-point value: " + value);
            }
            return BigInteger.valueOf(number.longValue());
        }
        String text = asString(value).trim();
        try {
            if (text.indexOf('.') >= 0 || text.indexOf('e') >= 0 || text.indexOf('E') >= 0) {
                return new BigDecimal(text).stripTrailingZeros().toBigIntegerExact();
            }
            return new BigInteger(text);
        } catch (NumberFormatException | ArithmeticException ex) {
            throw new IllegalArgumentException("not an integer primary key value: " + text, ex);
        }
    }

    private static Object bindInteger(Object value) {
        BigInteger number = toBigInteger(value);
        if (number.bitLength() < 64) {
            return number.longValue();
        }
        return new BigDecimal(number);
    }

    static String rtrim(String value) {
        int end = value.length();
        while (end > 0 && value.charAt(end - 1) == ' ') {
            end--;
        }
        return end == value.length() ? value : value.substring(0, end);
    }

    static String asString(Object value) {
        if (value instanceof String text) {
            return text;
        }
        if (value instanceof Character character) {
            return String.valueOf(character);
        }
        if (value instanceof byte[] bytes) {
            throw new IllegalArgumentException("binary primary key values are not allowed");
        }
        return String.valueOf(value);
    }

    static String normalizedTypeName(ColumnDefinition column) {
        String name = column.getTypeName();
        if (name == null || name.isBlank()) {
            return "";
        }
        String normalized = name.trim().toUpperCase(Locale.ROOT);
        int paren = normalized.indexOf('(');
        if (paren > 0) {
            normalized = normalized.substring(0, paren).trim();
        }
        if (normalized.endsWith(" UNSIGNED")) {
            normalized = normalized.substring(0, normalized.length() - " UNSIGNED".length()).trim();
        }
        return normalized.replaceAll("\\s+", " ");
    }

    static String displayType(ColumnDefinition column) {
        String name = column.getTypeName();
        if (name != null && !name.isBlank()) {
            return name;
        }
        return "JDBC type " + column.getJdbcType();
    }

    private static boolean isInteger(int jdbcType, String typeName) {
        if (jdbcType == Types.TINYINT || jdbcType == Types.SMALLINT
                || jdbcType == Types.INTEGER || jdbcType == Types.BIGINT) {
            return !isBoolean(jdbcType, typeName);
        }
        return typeName.equals("INT")
                || typeName.equals("INTEGER")
                || typeName.equals("TINYINT")
                || typeName.equals("SMALLINT")
                || typeName.equals("MEDIUMINT")
                || typeName.equals("BIGINT")
                || typeName.equals("INT2")
                || typeName.equals("INT4")
                || typeName.equals("INT8")
                || typeName.equals("SERIAL")
                || typeName.equals("BIGSERIAL")
                || typeName.equals("SMALLSERIAL");
    }

    private static boolean isCharFixed(int jdbcType, String typeName) {
        if (jdbcType == Types.CHAR || jdbcType == Types.NCHAR) {
            return !isVarcharName(typeName);
        }
        return typeName.equals("CHAR")
                || typeName.equals("NCHAR")
                || typeName.equals("CHARACTER")
                || typeName.equals("NATIONAL CHAR")
                || typeName.equals("NATIONAL CHARACTER");
    }

    private static boolean isVarcharOrText(int jdbcType, String typeName) {
        if (jdbcType == Types.VARCHAR || jdbcType == Types.NVARCHAR
                || jdbcType == Types.LONGVARCHAR || jdbcType == Types.LONGNVARCHAR
                || jdbcType == Types.CLOB || jdbcType == Types.NCLOB) {
            return true;
        }
        return isVarcharName(typeName)
                || typeName.equals("TEXT")
                || typeName.equals("TINYTEXT")
                || typeName.equals("MEDIUMTEXT")
                || typeName.equals("LONGTEXT")
                || typeName.equals("CITEXT");
    }

    private static boolean isVarcharName(String typeName) {
        return typeName.equals("VARCHAR")
                || typeName.equals("NVARCHAR")
                || typeName.equals("VARCHAR2")
                || typeName.equals("CHARACTER VARYING")
                || typeName.equals("CHAR VARYING")
                || typeName.equals("NATIONAL CHAR VARYING")
                || typeName.equals("NATIONAL CHARACTER VARYING")
                || typeName.equals("NCHAR VARYING");
    }

    private static boolean isBoolean(int jdbcType, String typeName) {
        return jdbcType == Types.BOOLEAN
                || jdbcType == Types.BIT
                || typeName.equals("BOOLEAN")
                || typeName.equals("BOOL")
                || typeName.equals("BIT");
    }

    private static boolean isDecimalOrFloat(int jdbcType, String typeName) {
        return jdbcType == Types.DECIMAL
                || jdbcType == Types.NUMERIC
                || jdbcType == Types.FLOAT
                || jdbcType == Types.REAL
                || jdbcType == Types.DOUBLE
                || typeName.equals("DECIMAL")
                || typeName.equals("NUMERIC")
                || typeName.equals("NUMBER")
                || typeName.equals("FLOAT")
                || typeName.equals("REAL")
                || typeName.equals("DOUBLE")
                || typeName.equals("DOUBLE PRECISION")
                || typeName.equals("MONEY");
    }

    private static boolean isDateTime(int jdbcType, String typeName) {
        return jdbcType == Types.DATE
                || jdbcType == Types.TIME
                || jdbcType == Types.TIMESTAMP
                || jdbcType == Types.TIME_WITH_TIMEZONE
                || jdbcType == Types.TIMESTAMP_WITH_TIMEZONE
                || typeName.equals("DATE")
                || typeName.equals("TIME")
                || typeName.equals("DATETIME")
                || typeName.equals("TIMESTAMP")
                || typeName.equals("TIMESTAMPTZ")
                || typeName.equals("TIMETZ")
                || typeName.equals("YEAR")
                || typeName.equals("INTERVAL");
    }

    private static boolean isBinary(int jdbcType, String typeName) {
        return jdbcType == Types.BINARY
                || jdbcType == Types.VARBINARY
                || jdbcType == Types.LONGVARBINARY
                || jdbcType == Types.BLOB
                || typeName.equals("BINARY")
                || typeName.equals("VARBINARY")
                || typeName.equals("TINYBLOB")
                || typeName.equals("BLOB")
                || typeName.equals("MEDIUMBLOB")
                || typeName.equals("LONGBLOB")
                || typeName.equals("BYTE")
                || typeName.equals("BYTES")
                || typeName.equals("BYTEA")
                || typeName.equals("RAW")
                || typeName.equals("UUID");
    }

    private static ColumnDefinition column(List<ColumnDefinition> columns, String name) {
        if (columns == null || name == null) {
            return null;
        }
        for (ColumnDefinition column : columns) {
            if (name.equalsIgnoreCase(column.getName())) {
                return column;
            }
        }
        return null;
    }

    /** Thrown when a table's primary key is not an allowed integer or string type. */
    public static final class UnsupportedPrimaryKeyException extends IllegalArgumentException {

        private final TableId table;
        private final String column;
        private final String typeName;

        public UnsupportedPrimaryKeyException(TableId table, String column, String typeName, String message) {
            super(message);
            this.table = table;
            this.column = column;
            this.typeName = typeName;
        }

        public TableId table() {
            return table;
        }

        public String column() {
            return column;
        }

        public String typeName() {
            return typeName;
        }
    }
}
