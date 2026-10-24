package io.opendrs.engine.replay.connection;

import io.opendrs.engine.replay.ReplayConfig;
import io.opendrs.engine.replay.dialect.DatabaseDialect;
import java.sql.Connection;
import java.sql.SQLException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Cached JDBC connection obtained from {@link ConnectionProvider}.
 */
public class JdbcConnection {
    private static final Logger LOGGER = LoggerFactory.getLogger(JdbcConnection.class);

    private final DatabaseDialect databaseDialect;
    private final ReplayConfig config;
    private volatile Connection connection;

    public JdbcConnection(DatabaseDialect databaseDialect, ReplayConfig config) {
        this.databaseDialect = databaseDialect;
        this.config = config;
    }

    /**
     * get a valid connection
     */
    public synchronized Connection connection() throws SQLException {
        if (connection == null) {
            connection = databaseDialect.getConnection();
            return connection;
        }
        if (isValid(connection)) {
            return connection;
        }
        closeProvider();
        connection = databaseDialect.getConnection();
        return connection;
    }

    private boolean isValid(Connection current) {
        try {
            return current != null && !current.isClosed() && current.isValid(3000);
        } catch (SQLException e) {
            return false;
        }
    }

    public void close() {
        closeProvider();
        connection = null;
    }

    /**
     * Closes only the connection this shell is holding. Never touches the provider: it may be
     * shared by every parallel applier worker, and one worker's reconnect or release must not
     * kill the others' connections.
     */
    private void closeProvider() {
        if (connection != null) {
            try {
                connection.close();
            } catch (SQLException e) {
                LOGGER.trace("failed to close connection, ignore it", e);
            }
            connection = null;
        }
    }
}
