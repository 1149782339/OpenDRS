package io.opendrs.sink.connection;

import java.sql.Connection;
import java.sql.SQLException;

/**
 * Supplies the target JDBC connection used by the applier. The service module opens the
 * connection (via its own factory) and hands it in; this module never reads
 * {@code ConnectionInfo} or Spring beans.
 */
public interface ConnectionProvider extends AutoCloseable {

    Connection getConnection() throws SQLException;

    @Override
    void close();
}
