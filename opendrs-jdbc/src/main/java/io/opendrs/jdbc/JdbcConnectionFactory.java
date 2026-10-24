package io.opendrs.jdbc;

import io.opendrs.common.metadata.DatabaseType;
import io.opendrs.jdbc.dialect.DbDialect;
import io.opendrs.jdbc.dialect.DbDialects;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

public class JdbcConnectionFactory {

    public JdbcConnection open(JdbcConnectionConfig config) {
        DbDialect dialect = DbDialects.of(config.getType());
        String url;
        try {
            url = dialect.jdbcUrl(config);
        } catch (IllegalArgumentException ex) {
            throw new JdbcException(ex.getMessage(), ex);
        }
        Properties props = new Properties();
        props.setProperty("user", config.getUsername());
        props.setProperty("password", config.getPassword() == null ? "" : config.getPassword());
        dialect.applyConnectProperties(props);

        java.sql.Connection raw = null;
        try {
            raw = DriverManager.getConnection(url, props);
            raw.setAutoCommit(true);
            DriverConnection connection = new DriverConnection(config.getType(), raw, dialect);
            dialect.afterConnect(connection, config);
            return connection;
        } catch (SQLException ex) {
            closeQuietly(raw);
            throw wrap(ex, config);
        } catch (RuntimeException ex) {
            closeQuietly(raw);
            if (ex instanceof JdbcException) {
                throw ex;
            }
            throw wrap(ex, config);
        }
    }

    private static JdbcException wrap(SQLException ex, JdbcConnectionConfig config) {
        return new JdbcException(
                "Connection test failed: " + config.getType() + " " + config.getHost() + ":" + config.getPort()
                        + " — " + sanitize(ex.getMessage()),
                ex);
    }

    private static JdbcException wrap(RuntimeException ex, JdbcConnectionConfig config) {
        return new JdbcException(
                "Connection test failed: " + config.getType() + " " + config.getHost() + ":" + config.getPort()
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

    private static void closeQuietly(java.sql.Connection raw) {
        if (raw == null) {
            return;
        }
        try {
            raw.close();
        } catch (SQLException ignored) {
            // already converting the original failure
        }
    }

    public static final class DriverConnection implements JdbcConnection {

        private final DatabaseType type;
        private final java.sql.Connection raw;
        private final DbDialect dialect;

        public DriverConnection(DatabaseType type, java.sql.Connection raw, DbDialect dialect) {
            this.type = type;
            this.raw = raw;
            this.dialect = dialect;
        }

        @Override
        public DatabaseType type() {
            return type;
        }

        @Override
        public void ping() {
            queryOne(dialect.testSql(), rs -> rs.getInt(1));
        }

        @Override
        public java.sql.Connection unwrap() {
            return raw;
        }

        @Override
        public <T> T queryOne(String sql, RowMapper<T> mapper) {
            List<T> rows = queryList(sql, mapper);
            return rows.isEmpty() ? null : rows.getFirst();
        }

        @Override
        public <T> List<T> queryList(String sql, RowMapper<T> mapper) {
            return queryList(sql, mapper, new Object[0]);
        }

        @Override
        public <T> List<T> queryList(String sql, RowMapper<T> mapper, Object... params) {
            try (PreparedStatement statement = raw.prepareStatement(sql)) {
                if (params != null) {
                    for (int i = 0; i < params.length; i++) {
                        statement.setObject(i + 1, params[i]);
                    }
                }
                try (ResultSet rs = statement.executeQuery()) {
                    List<T> rows = new ArrayList<>();
                    while (rs.next()) {
                        rows.add(mapper.map(rs));
                    }
                    return rows;
                }
            } catch (SQLException ex) {
                throw wrapSql(ex);
            }
        }

        @Override
        public void execute(String sql) {
            try (Statement statement = raw.createStatement()) {
                statement.execute(sql);
            } catch (SQLException ex) {
                throw wrapSql(ex);
            }
        }

        @Override
        public void close() {
            try {
                raw.close();
            } catch (SQLException ex) {
                throw wrapSql(ex);
            }
        }

        private JdbcException wrapSql(SQLException ex) {
            return new JdbcException(
                    "Connection test failed: " + type + " — " + sanitize(ex.getMessage()), ex);
        }
    }
}
