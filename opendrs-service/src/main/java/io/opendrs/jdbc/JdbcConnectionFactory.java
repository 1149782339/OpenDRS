package io.opendrs.jdbc;

import io.opendrs.common.error.AppException;
import io.opendrs.common.error.ErrorCode;
import io.opendrs.jdbc.dialect.DbDialect;
import io.opendrs.jdbc.dialect.DbDialects;
import io.opendrs.migration.domain.ConnectionInfo;
import io.opendrs.migration.domain.DbType;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import org.springframework.stereotype.Component;

@Component
public class JdbcConnectionFactory {

    public JdbcConnection open(ConnectionInfo info) {
        DbDialect dialect = DbDialects.of(info.getType());
        String url;
        try {
            url = dialect.jdbcUrl(info);
        } catch (IllegalArgumentException ex) {
            throw AppException.of(ErrorCode.PARAM_INVALID, ex.getMessage());
        }
        Properties props = new Properties();
        props.setProperty("user", info.getUsername());
        props.setProperty("password", info.getPassword() == null ? "" : info.getPassword());
        dialect.applyConnectProperties(props);

        java.sql.Connection raw = null;
        try {
            raw = DriverManager.getConnection(url, props);
            raw.setAutoCommit(true);
            DriverConnection connection = new DriverConnection(info.getType(), raw, dialect);
            dialect.afterConnect(connection, info);
            return connection;
        } catch (SQLException ex) {
            closeQuietly(raw);
            throw wrap(ex, info);
        } catch (RuntimeException ex) {
            closeQuietly(raw);
            if (ex instanceof AppException app) {
                throw app;
            }
            throw wrap(ex, info);
        }
    }

    static AppException wrap(SQLException ex, ConnectionInfo info) {
        return AppException.of(
                ErrorCode.CONNECTION_TEST_FAILED,
                "Connection test failed: " + info.getType() + " " + info.getHost() + ":" + info.getPort()
                        + " — " + sanitize(ex.getMessage()));
    }

    private static AppException wrap(RuntimeException ex, ConnectionInfo info) {
        return AppException.of(
                ErrorCode.CONNECTION_TEST_FAILED,
                "Connection test failed: " + info.getType() + " " + info.getHost() + ":" + info.getPort()
                        + " — " + sanitize(ex.getMessage()));
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

        private final DbType type;
        private final java.sql.Connection raw;
        private final DbDialect dialect;

        public DriverConnection(DbType type, java.sql.Connection raw, DbDialect dialect) {
            this.type = type;
            this.raw = raw;
            this.dialect = dialect;
        }

        @Override
        public DbType type() {
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

        private AppException wrapSql(SQLException ex) {
            return AppException.of(
                    ErrorCode.CONNECTION_TEST_FAILED,
                    "Connection test failed: " + type + " — " + sanitize(ex.getMessage()));
        }
    }
}
