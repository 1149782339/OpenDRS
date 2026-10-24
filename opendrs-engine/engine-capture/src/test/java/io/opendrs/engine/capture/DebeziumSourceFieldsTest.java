package io.opendrs.engine.capture;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.opendrs.common.metadata.DatabaseType;
import io.opendrs.jdbc.JdbcConnectionConfig;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DebeziumSourceFieldsTest {

    @Test
    void mysqlMapsHostPortUserPasswordDbAndExtras() {
        Map<String, String> fields = DebeziumSourceFields.of(mysql());
        assertEquals("10.0.0.2", fields.get("database.hostname"));
        assertEquals("3306", fields.get("database.port"));
        assertEquals("cdc", fields.get("database.user"));
        assertEquals("secret", fields.get("database.password"));
        assertEquals("hr", fields.get("database.dbname"));
        assertEquals("disabled", fields.get("database.ssl.mode"));
        assertEquals("UTC", fields.get("database.connectionTimeZone"));
    }

    @Test
    void mysqlWithoutExtrasHasNoSslOrTimezoneFields() {
        JdbcConnectionConfig config = mysqlBuilder().extras(Map.of()).build();

        Map<String, String> fields = DebeziumSourceFields.of(config);
        assertEquals("10.0.0.2", fields.get("database.hostname"));
        assertEquals(null, fields.get("database.ssl.mode"));
        assertEquals(null, fields.get("database.connectionTimeZone"));
    }

    @Test
    void postgresqlMapsBaseFieldsOnly() {
        JdbcConnectionConfig config = JdbcConnectionConfig.builder()
                .type(DatabaseType.POSTGRESQL)
                .host("10.0.0.3")
                .port(5432)
                .database("appdb")
                .username("cdc")
                .password("secret")
                .extras(Map.of("sslmode", "require"))
                .build();

        Map<String, String> fields = DebeziumSourceFields.of(config);
        assertEquals("10.0.0.3", fields.get("database.hostname"));
        assertEquals("appdb", fields.get("database.dbname"));
        assertEquals(null, fields.get("database.ssl.mode"));
    }

    private static JdbcConnectionConfig mysql() {
        return mysqlBuilder().build();
    }

    private static JdbcConnectionConfig.Builder mysqlBuilder() {
        return JdbcConnectionConfig.builder()
                .type(DatabaseType.MYSQL)
                .host("10.0.0.2")
                .port(3306)
                .database("hr")
                .username("cdc")
                .password("secret")
                .extras(Map.of("useSsl", false, "serverTimezone", "UTC"));
    }
}
