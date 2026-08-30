package io.opendrs.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.opendrs.jdbc.dialect.DbDialects;
import io.opendrs.migration.domain.ConnectionInfo;
import io.opendrs.migration.domain.DbType;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class JdbcUrlBuilderTest {

    @Test
    void mysqlAddsSslAndTimezoneQueryParams() {
        ConnectionInfo info = mysql("hr");
        info.setExtra(Map.of("useSsl", true, "serverTimezone", "Asia/Shanghai"));

        assertEquals(
                "jdbc:mysql://10.0.0.2:3306/hr?useSSL=true&serverTimezone=Asia%2FShanghai",
                JdbcUrlBuilder.url(info));
        assertEquals(JdbcUrlBuilder.url(info), DbDialects.of(DbType.MYSQL).jdbcUrl(info));
    }

    @Test
    void mysqlWithoutExtraHasNoQueryString() {
        assertEquals("jdbc:mysql://10.0.0.2:3306/hr", JdbcUrlBuilder.url(mysql("hr")));
    }

    @Test
    void mysqlUseSslFalse() {
        ConnectionInfo info = mysql("hr");
        info.setExtra(Map.of("useSsl", false));

        assertEquals("jdbc:mysql://10.0.0.2:3306/hr?useSSL=false", JdbcUrlBuilder.url(info));
    }

    @Test
    void oracleServiceIsDefault() {
        ConnectionInfo info = oracle("ORCL");
        info.setExtra(Map.of("pdb", "ORCLPDB1"));

        assertEquals("jdbc:oracle:thin:@//10.0.0.1:1521/ORCL", JdbcUrlBuilder.url(info));
        assertEquals(JdbcUrlBuilder.url(info), DbDialects.of(DbType.ORACLE).jdbcUrl(info));
    }

    @Test
    void oracleServiceExplicit() {
        ConnectionInfo info = oracle("ORCL");
        info.setExtra(Map.of("connectionType", "SERVICE"));

        assertEquals("jdbc:oracle:thin:@//10.0.0.1:1521/ORCL", JdbcUrlBuilder.url(info));
    }

    @Test
    void oracleSidUsesColonForm() {
        ConnectionInfo info = oracle("ORCL");
        info.setExtra(Map.of("connectionType", "SID", "pdb", "ORCLPDB1"));

        assertEquals("jdbc:oracle:thin:@10.0.0.1:1521:ORCL", JdbcUrlBuilder.url(info));
        assertEquals(JdbcUrlBuilder.url(info), DbDialects.of(DbType.ORACLE).jdbcUrl(info));
    }

    @Test
    void oraclePdbDoesNotChangeUrl() {
        ConnectionInfo withPdb = oracle("ORCL");
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("connectionType", "SERVICE");
        extra.put("pdb", "ORCLPDB1");
        extra.put("unknown", "keep");
        withPdb.setExtra(extra);

        ConnectionInfo withoutPdb = oracle("ORCL");
        withoutPdb.setExtra(Map.of("connectionType", "SERVICE"));

        String with = JdbcUrlBuilder.url(withPdb);
        assertEquals(JdbcUrlBuilder.url(withoutPdb), with);
        assertFalse(with.toLowerCase().contains("pdb"));
        assertFalse(with.contains("ORCLPDB1"));
    }

    @Test
    void postgresqlUrlAndSslmodeQuery() {
        ConnectionInfo info = postgresql("appdb");
        assertEquals("jdbc:postgresql://10.0.0.3:5432/appdb", JdbcUrlBuilder.url(info));
        assertEquals(JdbcUrlBuilder.url(info), DbDialects.of(DbType.POSTGRESQL).jdbcUrl(info));

        info.setExtra(Map.of("sslmode", "require", "ssl", true, "currentSchema", "app"));
        assertEquals(
                "jdbc:postgresql://10.0.0.3:5432/appdb?sslmode=require&ssl=true&currentSchema=app",
                JdbcUrlBuilder.url(info));
        assertEquals(JdbcUrlBuilder.url(info), DbDialects.of(DbType.POSTGRESQL).jdbcUrl(info));
    }

    @Test
    void oracleRejectsUnknownConnectionType() {
        ConnectionInfo info = oracle("ORCL");
        info.setExtra(Map.of("connectionType", "TNS"));

        assertThrows(IllegalArgumentException.class, () -> JdbcUrlBuilder.url(info));
    }

    private static ConnectionInfo mysql(String database) {
        ConnectionInfo info = new ConnectionInfo();
        info.setType(DbType.MYSQL);
        info.setHost("10.0.0.2");
        info.setPort(3306);
        info.setDbName(database);
        info.setUsername("drs");
        info.setPassword("secret");
        return info;
    }

    private static ConnectionInfo postgresql(String database) {
        ConnectionInfo info = new ConnectionInfo();
        info.setType(DbType.POSTGRESQL);
        info.setHost("10.0.0.3");
        info.setPort(5432);
        info.setDbName(database);
        info.setUsername("drs");
        info.setPassword("secret");
        return info;
    }

    private static ConnectionInfo oracle(String database) {
        ConnectionInfo info = new ConnectionInfo();
        info.setType(DbType.ORACLE);
        info.setHost("10.0.0.1");
        info.setPort(1521);
        info.setDbName(database);
        info.setUsername("cdc");
        info.setPassword("secret");
        return info;
    }
}
