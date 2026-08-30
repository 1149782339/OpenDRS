package io.opendrs.jdbc;

import io.opendrs.common.error.AppException;
import io.opendrs.common.error.ErrorCode;
import io.opendrs.migration.domain.ConnectionInfo;
import io.opendrs.migration.domain.DbType;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Properties;
import java.util.regex.Pattern;

public final class JdbcConnections {

    private static final int CONNECT_TIMEOUT_MS = 5000;
    private static final Pattern ORACLE_IDENT = Pattern.compile("[A-Za-z][A-Za-z0-9_$#]*");

    private JdbcConnections() {
    }

    public static JdbcConnection open(ConnectionInfo info) {
        String url;
        try {
            url = JdbcUrlBuilder.url(info);
        } catch (IllegalArgumentException ex) {
            throw AppException.of(ErrorCode.PARAM_INVALID, ex.getMessage());
        }
        Properties props = new Properties();
        props.setProperty("user", info.getUsername());
        props.setProperty("password", info.getPassword() == null ? "" : info.getPassword());
        if (info.getType() == DbType.MYSQL) {
            props.setProperty("connectTimeout", String.valueOf(CONNECT_TIMEOUT_MS));
        } else if (info.getType() == DbType.ORACLE) {
            props.setProperty("oracle.net.CONNECT_TIMEOUT", String.valueOf(CONNECT_TIMEOUT_MS));
        }

        java.sql.Connection raw = null;
        try {
            raw = DriverManager.getConnection(url, props);
            raw.setAutoCommit(true);
            DriverConnection connection = new DriverConnection(info.getType(), raw);
            switchOraclePdb(info, connection);
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

    private static void switchOraclePdb(ConnectionInfo info, DriverConnection connection) {
        if (info.getType() != DbType.ORACLE) {
            return;
        }
        String pdb = JdbcUrlBuilder.extraString(info, "pdb");
        if (pdb == null || pdb.isBlank()) {
            return;
        }
        connection.execute("ALTER SESSION SET CONTAINER = " + quoteOracleIdent(pdb.trim()));
    }

    static String quoteOracleIdent(String ident) {
        if (!ORACLE_IDENT.matcher(ident).matches()) {
            throw AppException.of(ErrorCode.CONNECTION_TEST_FAILED, "Invalid Oracle PDB name");
        }
        return "\"" + ident + "\"";
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

    static String sanitize(String message) {
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

    private static final class DriverConnection implements JdbcConnection {

        private final DbType type;
        private final java.sql.Connection raw;

        private DriverConnection(DbType type, java.sql.Connection raw) {
            this.type = type;
            this.raw = raw;
        }

        @Override
        public DbType type() {
            return type;
        }

        @Override
        public void ping() {
            String sql = type == DbType.MYSQL ? "SELECT 1" : "SELECT 1 FROM DUAL";
            queryOne(sql, rs -> rs.getInt(1));
        }

        @Override
        public java.sql.Connection unwrap() {
            return raw;
        }

        @Override
        public <T> T queryOne(String sql, RowMapper<T> mapper) {
            try (Statement statement = raw.createStatement();
                    ResultSet rs = statement.executeQuery(sql)) {
                if (!rs.next()) {
                    return null;
                }
                return mapper.map(rs);
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
