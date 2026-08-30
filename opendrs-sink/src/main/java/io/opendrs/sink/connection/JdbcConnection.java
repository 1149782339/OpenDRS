/*
 *  Copyright DbSink Authors.
 *  This source code is licensed under the Apache License Version 2.0, available
 *  at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.opendrs.sink.connection;

import io.opendrs.sink.SinkConfig;
import io.opendrs.sink.dialect.DatabaseDialect;
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
    private final SinkConfig config;
    private volatile Connection connection;

    public JdbcConnection(DatabaseDialect databaseDialect, SinkConfig config) {
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

    private void closeProvider() {
        ConnectionProvider provider = config.getConnectionProvider();
        if (provider != null) {
            try {
                provider.close();
            } catch (RuntimeException e) {
                LOGGER.trace("failed to close connection provider, ignore it", e);
            }
        } else if (connection != null) {
            try {
                connection.close();
            } catch (SQLException e) {
                LOGGER.trace("failed to close connection, ignore it", e);
            }
        }
        connection = null;
    }
}
