package io.opendrs.engine.capture;

import io.opendrs.common.metadata.DatabaseType;
import io.opendrs.jdbc.JdbcConnectionConfig;
import io.opendrs.jdbc.JdbcUrlBuilder;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Debezium source connector fields built from a neutral {@link JdbcConnectionConfig}
 * ({@code database.hostname}, port, user, password, db, plus MySQL ssl/timezone extras).
 *
 * <p>Pure capture mechanism: the caller (service boundary) projects its stored connection onto
 * {@link JdbcConnectionConfig}; this class only turns that into connector properties.
 */
public final class DebeziumSourceFields {

    private DebeziumSourceFields() {
    }

    public static Map<String, String> of(JdbcConnectionConfig config) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("database.hostname", config.getHost());
        fields.put("database.port", String.valueOf(config.getPort()));
        fields.put("database.user", config.getUsername());
        fields.put("database.password", config.getPassword() == null ? "" : config.getPassword());
        fields.put("database.dbname", config.getDatabase());
        if (config.getType() == DatabaseType.MYSQL) {
            String useSsl = JdbcUrlBuilder.extraString(config, "useSsl");
            if (useSsl != null && !useSsl.isBlank()) {
                fields.put("database.ssl.mode", asBoolean(useSsl) ? "required" : "disabled");
            }
            String serverTimezone = JdbcUrlBuilder.extraString(config, "serverTimezone");
            if (serverTimezone != null && !serverTimezone.isBlank()) {
                fields.put("database.connectionTimeZone", serverTimezone);
            }
            // Same caching_sha2_password public-key problem as the plain JDBC URL: on non-SSL
            // connections the driver must be allowed to ask the server for its public key.
            String allowPublicKeyRetrieval = JdbcUrlBuilder.extraString(config, "allowPublicKeyRetrieval");
            if (allowPublicKeyRetrieval != null && !allowPublicKeyRetrieval.isBlank()) {
                fields.put("database.allowPublicKeyRetrieval", String.valueOf(asBoolean(allowPublicKeyRetrieval)));
            }
        }
        return fields;
    }

    private static boolean asBoolean(Object value) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        String text = String.valueOf(value).trim().toLowerCase(Locale.ROOT);
        return "true".equals(text) || "1".equals(text);
    }
}
