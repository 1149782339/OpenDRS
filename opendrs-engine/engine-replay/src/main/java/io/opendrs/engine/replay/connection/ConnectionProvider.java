package io.opendrs.engine.replay.connection;

import java.sql.Connection;
import java.sql.SQLException;

/**
 * Supplies the target JDBC connection used by the applier. The service module opens the
 * connection (via its own factory) and hands it in; this module never reads
 * {@code ConnectionInfo} or Spring beans.
 *
 * <p>Implementations must be stateless about connection ownership: every call may return a
 * new connection, and the caller ({@link JdbcConnection}) caches and closes what it received.
 * A task-level shared provider must therefore never cache "the" connection itself — doing so
 * pins every parallel applier worker onto one physical connection (and one non-thread-safe
 * {@link Connection}), which is both a throughput ceiling and a transaction-isolation hazard.
 */
@FunctionalInterface
public interface ConnectionProvider extends AutoCloseable {

    Connection getConnection() throws SQLException;

    /**
     * No-op by default: connection lifetime is owned by the {@link JdbcConnection} that
     * received it (one per applier worker), closed through the applier release chain.
     */
    @Override
    default void close() {
    }
}
