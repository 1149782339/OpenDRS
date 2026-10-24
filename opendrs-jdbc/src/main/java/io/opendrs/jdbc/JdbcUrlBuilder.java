package io.opendrs.jdbc;

import io.opendrs.jdbc.JdbcConnectionConfig;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Builds a JDBC URL from stored connection config. Oracle PDB is not part of the URL
 * (it is applied after connect with {@code ALTER SESSION SET CONTAINER}).
 */
public final class JdbcUrlBuilder {

    private JdbcUrlBuilder() {
    }

    public static String url(JdbcConnectionConfig config) {
        return switch (config.getType()) {
            case MYSQL -> mysqlUrl(config);
            case ORACLE -> oracleUrl(config);
            case POSTGRESQL -> postgresqlUrl(config);
        };
    }

    private static String mysqlUrl(JdbcConnectionConfig config) {
        String base = "jdbc:mysql://" + config.getHost() + ":" + config.getPort() + "/" + config.getDatabase();
        List<String> query = new ArrayList<>();
        Object useSsl = extraValue(config, "useSsl");
        if (useSsl != null) {
            query.add("useSSL=" + asBoolean(useSsl));
        }
        Object serverTimezone = extraValue(config, "serverTimezone");
        if (serverTimezone != null && !String.valueOf(serverTimezone).isBlank()) {
            query.add("serverTimezone=" + encode(String.valueOf(serverTimezone)));
        }
        // MySQL 8's default caching_sha2_password asks the server for its public key on
        // non-SSL connections, which the driver refuses to do unless explicitly allowed.
        Object allowPublicKeyRetrieval = extraValue(config, "allowPublicKeyRetrieval");
        if (allowPublicKeyRetrieval != null) {
            query.add("allowPublicKeyRetrieval=" + asBoolean(allowPublicKeyRetrieval));
        }
        if (query.isEmpty()) {
            return base;
        }
        return base + "?" + String.join("&", query);
    }

    private static String oracleUrl(JdbcConnectionConfig config) {
        String connectionType = extraString(config, "connectionType");
        boolean sid = connectionType != null && "SID".equalsIgnoreCase(connectionType);
        if (connectionType != null
                && !connectionType.isBlank()
                && !sid
                && !"SERVICE".equalsIgnoreCase(connectionType)) {
            throw new IllegalArgumentException("Unsupported Oracle connectionType: " + connectionType);
        }
        if (sid) {
            return "jdbc:oracle:thin:@" + config.getHost() + ":" + config.getPort() + ":" + config.getDatabase();
        }
        return "jdbc:oracle:thin:@//" + config.getHost() + ":" + config.getPort() + "/" + config.getDatabase();
    }

    private static String postgresqlUrl(JdbcConnectionConfig config) {
        String base = "jdbc:postgresql://" + config.getHost() + ":" + config.getPort() + "/" + config.getDatabase();
        List<String> query = new ArrayList<>();
        Object sslmode = extraValue(config, "sslmode");
        if (sslmode != null && !String.valueOf(sslmode).isBlank()) {
            query.add("sslmode=" + encode(String.valueOf(sslmode)));
        }
        Object ssl = extraValue(config, "ssl");
        if (ssl != null) {
            query.add("ssl=" + asBoolean(ssl));
        }
        Object currentSchema = extraValue(config, "currentSchema");
        if (currentSchema != null && !String.valueOf(currentSchema).isBlank()) {
            query.add("currentSchema=" + encode(String.valueOf(currentSchema)));
        }
        if (query.isEmpty()) {
            return base;
        }
        return base + "?" + String.join("&", query);
    }

    static Object extraValue(JdbcConnectionConfig config, String key) {
        Map<String, Object> extra = config.getExtras();
        if (extra == null) {
            return null;
        }
        return extra.get(key);
    }

    public static String extraString(JdbcConnectionConfig config, String key) {
        Object value = extraValue(config, key);
        return value == null ? null : String.valueOf(value);
    }

    private static String asBoolean(Object value) {
        if (value instanceof Boolean bool) {
            return bool ? "true" : "false";
        }
        String text = String.valueOf(value).trim().toLowerCase(Locale.ROOT);
        return ("true".equals(text) || "1".equals(text)) ? "true" : "false";
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
