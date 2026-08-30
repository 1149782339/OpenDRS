package io.opendrs.debezium;

import io.opendrs.jdbc.JdbcConnection;
import io.opendrs.jdbc.JdbcConnectionFactory;
import io.opendrs.migration.domain.ConnectionInfo;
import io.opendrs.sink.connection.ConnectionProvider;
import java.sql.Connection;
import java.sql.SQLException;

/**
 * Opens the target through the service {@link JdbcConnectionFactory} and exposes the raw JDBC
 * connection to {@code opendrs-sink}.
 */
public final class TargetConnectionProvider implements ConnectionProvider {

    private final JdbcConnectionFactory factory;
    private final ConnectionInfo target;
    private JdbcConnection owned;

    public TargetConnectionProvider(JdbcConnectionFactory factory, ConnectionInfo target) {
        this.factory = factory;
        this.target = target;
    }

    @Override
    public synchronized Connection getConnection() throws SQLException {
        if (owned == null) {
            owned = factory.open(target);
        }
        return owned.unwrap();
    }

    @Override
    public synchronized void close() {
        if (owned == null) {
            return;
        }
        try {
            owned.close();
        } finally {
            owned = null;
        }
    }
}
