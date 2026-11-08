package io.opendrs.engine.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.opendrs.engine.common.metadata.DatabaseType;
import java.util.Map;
import java.util.Properties;
import org.junit.jupiter.api.Test;

class ConnectionUrlTest {

    @Test
    void mysqlPatternResolvesHostPortDatabase() {
        assertEquals(
                "jdbc:mysql://10.0.0.2:3306/hr",
                JdbcConnection.resolveUrl(
                        MySqlConnection.URL_PATTERN,
                        mysql("hr", Map.of()),
                        Field.of(JdbcConnection.PORT, "3306")));
    }

    @Test
    void postgresPatternResolvesHostPortDatabase() {
        assertEquals(
                "jdbc:postgresql://10.0.0.3:5432/appdb",
                JdbcConnection.resolveUrl(
                        PostgresConnection.URL_PATTERN,
                        postgresql("appdb", Map.of()),
                        Field.of(JdbcConnection.PORT, "5432")));
    }

    @Test
    void oracleServiceIsDefault() {
        assertEquals(
                OracleConnection.SERVICE_URL_PATTERN,
                OracleConnection.urlPattern(oracle("ORCL", Map.of("pdb", "ORCLPDB1"))));
    }

    @Test
    void oracleSidUsesColonForm() {
        assertEquals(
                OracleConnection.SID_URL_PATTERN,
                OracleConnection.urlPattern(oracle("ORCL", Map.of("connectionType", "SID"))));
    }

    @Test
    void oracleRejectsUnknownConnectionType() {
        assertThrows(
                IllegalArgumentException.class,
                () -> OracleConnection.urlPattern(oracle("ORCL", Map.of("connectionType", "TNS"))));
    }

    @Test
    void mysqlExtrasBecomeDriverProperties() {
        Properties props = MySqlConnection.connectProperties(mysql("hr", Map.of(
                "useSsl", true,
                "serverTimezone", "Asia/Shanghai",
                "allowPublicKeyRetrieval", 1)));

        assertEquals("true", props.getProperty("useSSL"));
        assertEquals("Asia/Shanghai", props.getProperty("serverTimezone"));
        assertEquals("true", props.getProperty("allowPublicKeyRetrieval"));
        assertEquals("5000", props.getProperty("connectTimeout"));
        assertEquals("drs", props.getProperty("user"));
    }

    @Test
    void mysqlAbsentExtrasStayOutOfTheProperties() {
        Properties props = MySqlConnection.connectProperties(mysql("hr", Map.of()));

        assertNull(props.getProperty("useSSL"));
        assertNull(props.getProperty("serverTimezone"));
    }

    @Test
    void postgresExtrasBecomeDriverProperties() {
        Properties props = PostgresConnection.connectProperties(postgresql("appdb", Map.of(
                "sslmode", "require",
                "ssl", true,
                "currentSchema", "app")));

        assertEquals("require", props.getProperty("sslmode"));
        assertEquals("true", props.getProperty("ssl"));
        assertEquals("app", props.getProperty("currentSchema"));
        assertEquals("unspecified", props.getProperty("stringtype"));
        assertEquals("5", props.getProperty("connectTimeout"));
    }

    private static JdbcConnectionConfig mysql(String database, Map<String, Object> extras) {
        return JdbcConnectionConfig.builder()
                .type(DatabaseType.MYSQL)
                .host("10.0.0.2")
                .port(3306)
                .database(database)
                .username("drs")
                .password("secret")
                .extras(extras)
                .build();
    }

    private static JdbcConnectionConfig postgresql(String database, Map<String, Object> extras) {
        return JdbcConnectionConfig.builder()
                .type(DatabaseType.POSTGRESQL)
                .host("10.0.0.3")
                .port(5432)
                .database(database)
                .username("drs")
                .password("secret")
                .extras(extras)
                .build();
    }

    private static JdbcConnectionConfig oracle(String database, Map<String, Object> extras) {
        return JdbcConnectionConfig.builder()
                .type(DatabaseType.ORACLE)
                .host("10.0.0.1")
                .port(1521)
                .database(database)
                .username("cdc")
                .password("secret")
                .extras(extras)
                .build();
    }
}
