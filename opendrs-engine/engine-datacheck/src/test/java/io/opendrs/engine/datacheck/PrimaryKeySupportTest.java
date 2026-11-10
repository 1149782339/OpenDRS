package io.opendrs.engine.datacheck;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.opendrs.engine.common.metadata.ColumnDefinition;
import io.opendrs.engine.common.metadata.TableDefinition;
import io.opendrs.engine.common.metadata.TableId;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.Types;
import java.util.List;
import org.junit.jupiter.api.Test;

class PrimaryKeySupportTest {

    private static final TableId TABLE = TableId.of("dcheck", "t");

    @Test
    void rejectsNonIntegerOrStringPrimaryKeys() {
        assertRejected("dt", "DATE", Types.DATE);
        assertRejected("tm", "TIME", Types.TIME);
        assertRejected("ts", "TIMESTAMP", Types.TIMESTAMP);
        assertRejected("bin", "BINARY", Types.BINARY);
        assertRejected("vbin", "VARBINARY", Types.VARBINARY);
        assertRejected("dec", "DECIMAL", Types.DECIMAL);
        assertRejected("num", "NUMERIC", Types.NUMERIC);
        assertRejected("fl", "FLOAT", Types.FLOAT);
        assertRejected("dbl", "DOUBLE", Types.DOUBLE);
        assertRejected("flag", "BOOLEAN", Types.BOOLEAN);
        assertRejected("bit", "BIT", Types.BIT);
    }

    @Test
    void allowsIntegerAndStringPrimaryKeys() {
        PrimaryKeySupport.validate(TABLE, table("id", "BIGINT", Types.BIGINT));
        PrimaryKeySupport.validate(TABLE, table("id", "INT", Types.INTEGER));
        PrimaryKeySupport.validate(TABLE, table("id", "SMALLINT", Types.SMALLINT));
        PrimaryKeySupport.validate(TABLE, table("id", "TINYINT", Types.TINYINT));
        PrimaryKeySupport.validate(TABLE, table("c", "CHAR", Types.CHAR));
        PrimaryKeySupport.validate(TABLE, table("v", "VARCHAR", Types.VARCHAR));
        PrimaryKeySupport.validate(TABLE, table("t", "TEXT", Types.LONGVARCHAR));
        PrimaryKeySupport.validate(TABLE, table("id", "BIGINT UNSIGNED", Types.BIGINT));
    }

    @Test
    void errorNamesTableAndColumn() {
        PrimaryKeySupport.UnsupportedPrimaryKeyException ex = assertThrows(
                PrimaryKeySupport.UnsupportedPrimaryKeyException.class,
                () -> PrimaryKeySupport.validate(TABLE, table("dt", "DATE", Types.DATE)));
        assertEquals(TABLE, ex.table());
        assertEquals("dt", ex.column());
        assertTrue(ex.getMessage().contains("dcheck.t") || ex.getMessage().contains("dcheck"));
        assertTrue(ex.getMessage().contains("dt"));
        assertTrue(ex.getMessage().contains("DATE"));
    }

    @Test
    void integerEncodingIsCanonicalAcrossJdbcAndEventTypes() {
        ColumnDefinition col = column("id", "BIGINT", Types.BIGINT);
        String expected = "5";
        assertEquals(expected, PrimaryKeySupport.encodeColumn(TABLE, col, 5));
        assertEquals(expected, PrimaryKeySupport.encodeColumn(TABLE, col, 5L));
        assertEquals(expected, PrimaryKeySupport.encodeColumn(TABLE, col, (short) 5));
        assertEquals(expected, PrimaryKeySupport.encodeColumn(TABLE, col, (byte) 5));
        assertEquals(expected, PrimaryKeySupport.encodeColumn(TABLE, col, BigInteger.valueOf(5)));
        assertEquals(expected, PrimaryKeySupport.encodeColumn(TABLE, col, new BigDecimal("5")));
        assertEquals(expected, PrimaryKeySupport.encodeColumn(TABLE, col, new BigDecimal("5.0")));
        assertEquals(expected, PrimaryKeySupport.encodeColumn(TABLE, col, "5"));
        assertEquals(expected, PrimaryKeySupport.encodeColumn(TABLE, col, "05"));
    }

    @Test
    void unsignedBigintFromBigDecimalMatchesCanonicalDecimalString() {
        ColumnDefinition col = column("id", "BIGINT UNSIGNED", Types.BIGINT);
        BigDecimal unsigned = new BigDecimal("18446744073709551615");
        assertEquals("18446744073709551615", PrimaryKeySupport.encodeColumn(TABLE, col, unsigned));
        assertEquals(
                "18446744073709551615",
                PrimaryKeySupport.encodeColumn(TABLE, col, new BigInteger("18446744073709551615")));
        assertEquals("18446744073709551615", PrimaryKeySupport.encodeColumn(TABLE, col, "18446744073709551615"));
    }

    @Test
    void charPrimaryKeyStripsTrailingSpacesOnBothSides() {
        ColumnDefinition col = column("c", "CHAR", Types.CHAR);
        // JDBC getString on CHAR(8) includes padding; incremental event may or may not.
        assertEquals("abc", PrimaryKeySupport.encodeColumn(TABLE, col, "abc     "));
        assertEquals("abc", PrimaryKeySupport.encodeColumn(TABLE, col, "abc"));
        assertEquals("  abc", PrimaryKeySupport.encodeColumn(TABLE, col, "  abc   "));
    }

    @Test
    void varcharAndTextKeepTrailingSpaces() {
        ColumnDefinition varchar = column("v", "VARCHAR", Types.VARCHAR);
        ColumnDefinition text = column("t", "TEXT", Types.LONGVARCHAR);
        assertEquals("abc   ", PrimaryKeySupport.encodeColumn(TABLE, varchar, "abc   "));
        assertEquals("abc   ", PrimaryKeySupport.encodeColumn(TABLE, text, "abc   "));
    }

    @Test
    void fullAndIncrementalEncodingsMatchForAllowedTypes() {
        List<ColumnDefinition> integerPk = List.of(column("id", "BIGINT", Types.BIGINT));
        String fromJdbc = PrimaryKeySupport.encode(TABLE, integerPk, List.of(7L));
        String fromEvent = PrimaryKeySupport.encode(TABLE, integerPk, List.of(Integer.valueOf(7)));
        assertEquals(fromJdbc, fromEvent);

        List<ColumnDefinition> charPk = List.of(column("c", "CHAR", Types.CHAR));
        assertEquals(
                PrimaryKeySupport.encode(TABLE, charPk, List.of("x     ")),
                PrimaryKeySupport.encode(TABLE, charPk, List.of("x")));

        List<ColumnDefinition> varcharPk = List.of(column("v", "VARCHAR", Types.VARCHAR));
        assertEquals(
                PrimaryKeySupport.encode(TABLE, varcharPk, List.of("keep  ")),
                PrimaryKeySupport.encode(TABLE, varcharPk, List.of("keep  ")));
    }

    @Test
    void nullPrimaryKeyIsRejected() {
        ColumnDefinition col = column("id", "BIGINT", Types.BIGINT);
        assertThrows(IllegalArgumentException.class, () -> PrimaryKeySupport.encodeColumn(TABLE, col, null));
        assertThrows(IllegalArgumentException.class, () -> InconsistentRowStore.encode(java.util.Arrays.asList("1", null)));
    }

    @Test
    void bindIntegerUsesNumericType() {
        ColumnDefinition col = column("id", "BIGINT", Types.BIGINT);
        assertEquals(5L, PrimaryKeySupport.bindValue(TABLE, col, "5"));
        Object big = PrimaryKeySupport.bindValue(TABLE, col, new BigDecimal("18446744073709551615"));
        assertTrue(big instanceof BigDecimal || big instanceof BigInteger);
    }

    private static void assertRejected(String name, String type, int jdbcType) {
        PrimaryKeySupport.UnsupportedPrimaryKeyException ex = assertThrows(
                PrimaryKeySupport.UnsupportedPrimaryKeyException.class,
                () -> PrimaryKeySupport.validate(TABLE, table(name, type, jdbcType)),
                type);
        assertEquals(name, ex.column());
    }

    private static TableDefinition table(String name, String type, int jdbcType) {
        ColumnDefinition column = column(name, type, jdbcType);
        return TableDefinition.builder()
                .tableId(TABLE)
                .columnDefinitions(List.of(column))
                .primaryKeyColumNames(List.of(name))
                .build();
    }

    private static ColumnDefinition column(String name, String type, int jdbcType) {
        return new ColumnDefinition.Builder()
                .name(name)
                .typeName(type)
                .jdbcType(jdbcType)
                .length(64)
                .build();
    }
}
