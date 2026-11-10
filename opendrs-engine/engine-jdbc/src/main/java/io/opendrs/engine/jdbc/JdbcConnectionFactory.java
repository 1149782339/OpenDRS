package io.opendrs.engine.jdbc;

import java.sql.SQLException;

public class JdbcConnectionFactory {

    /**
     * Creates the typed connection and opens it eagerly, so connection failures still surface here
     * (and with the friendly {@code TYPE host:port — detail} message) rather than on first query.
     */
    public JdbcConnection open(JdbcConnectionConfig config) {
        JdbcConnection connection = null;
        try {
            connection = switch (config.getType()) {
                case MYSQL -> new MySqlConnection(config);
                case ORACLE -> new OracleConnection(config);
                case POSTGRESQL -> new PostgresConnection(config);
            };
            connection.connection();
            return connection;
        } catch (IllegalArgumentException ex) {
            closeQuietly(connection);
            throw new JdbcException(ex.getMessage(), ex);
        } catch (SQLException ex) {
            closeQuietly(connection);
            throw wrap(ex, config);
        } catch (RuntimeException ex) {
            closeQuietly(connection);
            if (ex instanceof JdbcException) {
                throw ex;
            }
            throw wrap(ex, config);
        }
    }

    private static void closeQuietly(JdbcConnection connection) {
        if (connection == null) {
            return;
        }
        try {
            connection.close();
        } catch (RuntimeException ignored) {
            // already failing with the original error
        }
    }

    // 消息只带「类型 主机:端口 — 详情」：语义前缀（如 "Connection test failed:"）由上层
    // i18n 模板补，同一个异常还会被 listReplicas / server_uuid / 预检查等场景复用，
    // 在这里写死测试语义会造成 "Connection test failed: Connection test failed: ..." 式冗余。
    private static JdbcException wrap(SQLException ex, JdbcConnectionConfig config) {
        return new JdbcException(
                config.getType() + " " + config.getHost() + ":" + config.getPort()
                        + " — " + sanitize(ex.getMessage()),
                ex);
    }

    private static JdbcException wrap(RuntimeException ex, JdbcConnectionConfig config) {
        return new JdbcException(
                config.getType() + " " + config.getHost() + ":" + config.getPort()
                        + " — " + sanitize(ex.getMessage()),
                ex);
    }

    public static String sanitize(String message) {
        if (message == null || message.isBlank()) {
            return "database connection failed";
        }
        String sanitized = message
                .replaceAll("(?i)password=[^;&\\s,)]*", "password=***")
                .replaceAll("(?i)user(?:name)?=[^;&\\s,)]*", "user=***")
                .replaceAll("(?i)jdbc:[^\\s]+", "[jdbc]");
        if (sanitized.length() > 180) {
            return sanitized.substring(0, 180);
        }
        return sanitized;
    }
}
