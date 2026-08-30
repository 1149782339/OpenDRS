package io.opendrs.jdbc;

import io.opendrs.migration.domain.ConnectionInfo;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Builds a JDBC URL from stored connection info. Oracle PDB is not part of the URL
 * (it is applied after connect with {@code ALTER SESSION SET CONTAINER}).
 */
public final class JdbcUrlBuilder {

    private JdbcUrlBuilder() {
    }

    public static String url(ConnectionInfo info) {
        return switch (info.getType()) {
            case MYSQL -> mysqlUrl(info);
            case ORACLE -> oracleUrl(info);
        };
    }

    private static String mysqlUrl(ConnectionInfo info) {
        String base = "jdbc:mysql://" + info.getHost() + ":" + info.getPort() + "/" + info.getDbName();
        List<String> query = new ArrayList<>();
        Object useSsl = extraValue(info, "useSsl");
        if (useSsl != null) {
            query.add("useSSL=" + asBoolean(useSsl));
        }
        Object serverTimezone = extraValue(info, "serverTimezone");
        if (serverTimezone != null && !String.valueOf(serverTimezone).isBlank()) {
            query.add("serverTimezone=" + encode(String.valueOf(serverTimezone)));
        }
        if (query.isEmpty()) {
            return base;
        }
        return base + "?" + String.join("&", query);
    }

    private static String oracleUrl(ConnectionInfo info) {
        String connectionType = extraString(info, "connectionType");
        boolean sid = connectionType != null && "SID".equalsIgnoreCase(connectionType);
        if (connectionType != null
                && !connectionType.isBlank()
                && !sid
                && !"SERVICE".equalsIgnoreCase(connectionType)) {
            throw new IllegalArgumentException("Unsupported Oracle connectionType: " + connectionType);
        }
        if (sid) {
            return "jdbc:oracle:thin:@" + info.getHost() + ":" + info.getPort() + ":" + info.getDbName();
        }
        return "jdbc:oracle:thin:@//" + info.getHost() + ":" + info.getPort() + "/" + info.getDbName();
    }

    static Object extraValue(ConnectionInfo info, String key) {
        Map<String, Object> extra = info.getExtra();
        if (extra == null) {
            return null;
        }
        return extra.get(key);
    }

    static String extraString(ConnectionInfo info, String key) {
        Object value = extraValue(info, key);
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
