package io.opendrs.engine.datacheck.incremental;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.opendrs.engine.datacheck.InconsistentRowStore;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.Date;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.sql.Time;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PrimaryKeyEncoderTest {

    private Connection connection;

    @BeforeEach
    void open() throws Exception {
        connection = DriverManager.getConnection("jdbc:h2:mem:pkenc_" + System.nanoTime());
        try (Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE pk_sample (
                      i INT,
                      l BIGINT,
                      s VARCHAR(64),
                      d DECIMAL(10,2),
                      dt DATE,
                      ts TIMESTAMP,
                      tm TIME,
                      b BOOLEAN,
                      bin VARBINARY(16)
                    )
                    """);
        }
    }

    @AfterEach
    void close() throws Exception {
        if (connection != null) {
            connection.close();
        }
    }

    @Test
    void matchesH2GetStringForCommonTypes() throws Exception {
        insertAndAssert("i", 42, 42);
        insertAndAssert("l", 99L, 99L);
        insertAndAssert("s", "hello", "hello");
        insertAndAssert("d", new BigDecimal("12.50"), new BigDecimal("12.50"));
        insertAndAssert("dt", Date.valueOf(LocalDate.of(2024, 1, 2)), LocalDate.of(2024, 1, 2));
        LocalDateTime dateTime = LocalDateTime.of(2024, 1, 2, 3, 4, 5);
        insertAndAssert("ts", Timestamp.valueOf(dateTime), dateTime);
        insertAndAssert("tm", Time.valueOf(LocalTime.of(6, 7, 8)), LocalTime.of(6, 7, 8));
        // BOOLEAN getString is driver-specific (H2: TRUE); encoder uses MySQL 1/0.
        insertAndAssert("bin", "AB".getBytes(StandardCharsets.ISO_8859_1),
                "AB".getBytes(StandardCharsets.ISO_8859_1));
    }

    @Test
    void encodeJoinsWithNullSeparator() {
        assertEquals(
                InconsistentRowStore.encode(List.of("1", "a")),
                PrimaryKeyEncoder.encode(List.of(1, "a")));
    }

    @Test
    void nullValue() {
        assertNull(PrimaryKeyEncoder.toJdbcString(null));
    }

    @Test
    void booleanAsMysqlTinyint() {
        assertEquals("1", PrimaryKeyEncoder.toJdbcString(true));
        assertEquals("0", PrimaryKeyEncoder.toJdbcString(false));
    }

    private void insertAndAssert(String column, Object jdbcValue, Object eventValue) throws Exception {
        try (Statement statement = connection.createStatement()) {
            statement.execute("DELETE FROM pk_sample");
        }
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO pk_sample (" + column + ") VALUES (?)")) {
            insert.setObject(1, jdbcValue);
            insert.executeUpdate();
        }
        try (Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery("SELECT " + column + " FROM pk_sample")) {
            result.next();
            assertEquals(result.getString(1), PrimaryKeyEncoder.toJdbcString(eventValue),
                    "column " + column);
        }
    }
}
