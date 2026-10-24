package io.opendrs.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.opendrs.common.metadata.DatabaseType;
import io.opendrs.jdbc.dialect.DbDialects;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class JdbcUrlBuilderTest {

    @Test
    void mysqlAddsSslAndTimezoneQueryParams() {
        JdbcConnectionConfig config = mysql("hr", Map.of("useSsl", true, "serverTimezone", "Asia/Shanghai"));

        assertEquals(
                "jdbc:mysql://10.0.0.2:3306/hr?useSSL=true&serverTimezone=Asia%2FShanghai",
                JdbcUrlBuilder.url(config));
        assertEquals(JdbcUrlBuilder.url(config), DbDialects.of(DatabaseType.MYSQL).jdbcUrl(config));
    }

    @Test
    void mysqlWithoutExtraHasNoQueryString() {
        assertEquals("jdbc:mysql://10.0.0.2:3306/hr", JdbcUrlBuilder.url(mysql("hr", Map.of())));
    }

    @Test
    void mysqlUseSslFalse() {
        JdbcConnectionConfig config = mysql("hr", Map.of("useSsl", false));

        assertEquals("jdbc:mysql://10.0.0.2:3306/hr?useSSL=false", JdbcUrlBuilder.url(config));
    }

    @Test
    void oracleServiceIsDefault() {
        JdbcConnectionConfig config = oracle("ORCL", Map.of("pdb", "ORCLPDB1"));

        assertEquals("jdbc:oracle:thin:@//10.0.0.1:1521/ORCL", JdbcUrlBuilder.url(config));
        assertEquals(JdbcUrlBuilder.url(config), DbDialects.of(DatabaseType.ORACLE).jdbcUrl(config));
    }

    @Test
    void oracleServiceExplicit() {
        JdbcConnectionConfig config = oracle("ORCL", Map.of("connectionType", "SERVICE"));

        assertEquals("jdbc:oracle:thin:@//10.0.0.1:1521/ORCL", JdbcUrlBuilder.url(config));
    }

    @Test
    void oracleSidUsesColonForm() {
        JdbcConnectionConfig config = oracle("ORCL", Map.of("connectionType", "SID", "pdb", "ORCLPDB1"));

        assertEquals("jdbc:oracle:thin:@10.0.0.1:1521:ORCL", JdbcUrlBuilder.url(config));
        assertEquals(JdbcUrlBuilder.url(config), DbDialects.of(DatabaseType.ORACLE).jdbcUrl(config));
    }

    @Test
    void oraclePdbDoesNotChangeUrl() {
        Map<String, Object> withPdbExtras = new LinkedHashMap<>();
        withPdbExtras.put("connectionType", "SERVICE");
        withPdbExtras.put("pdb", "ORCLPDB1");
        withPdbExtras.put("unknown", "keep");
        JdbcConnectionConfig withPdb = oracle("ORCL", withPdbExtras);

        JdbcConnectionConfig withoutPdb = oracle("ORCL", Map.of("connectionType", "SERVICE"));

        String with = JdbcUrlBuilder.url(withPdb);
        assertEquals(JdbcUrlBuilder.url(withoutPdb), with);
        assertFalse(with.toLowerCase().contains("pdb"));
        assertFalse(with.contains("ORCLPDB1"));
    }

    @Test
    void postgresqlUrlAndSslmodeQuery() {
        JdbcConnectionConfig config = postgresql("appdb", Map.of());
        assertEquals("jdbc:postgresql://10.0.0.3:5432/appdb", JdbcUrlBuilder.url(config));
        assertEquals(JdbcUrlBuilder.url(config), DbDialects.of(DatabaseType.POSTGRESQL).jdbcUrl(config));

        config = postgresql(
                "appdb",
                Map.of("sslmode", "require", "ssl", true, "currentSchema", "app"));
        assertEquals(
                "jdbc:postgresql://10.0.0.3:5432/appdb?sslmode=require&ssl=true&currentSchema=app",
                JdbcUrlBuilder.url(config));
        assertEquals(JdbcUrlBuilder.url(config), DbDialects.of(DatabaseType.POSTGRESQL).jdbcUrl(config));
    }

    @Test
    void oracleRejectsUnknownConnectionType() {
        JdbcConnectionConfig config = oracle("ORCL", Map.of("connectionType", "TNS"));

        assertThrows(IllegalArgumentException.class, () -> JdbcUrlBuilder.url(config));
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
