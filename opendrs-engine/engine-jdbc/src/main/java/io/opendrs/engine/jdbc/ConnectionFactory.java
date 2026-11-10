package io.opendrs.engine.jdbc;

import java.sql.SQLException;

/**
 * Establishes one raw JDBC connection from the supplied configuration.
 *
 * <p>This is the seam between "how a socket is obtained" (URL, driver, auth, proxy) and "what the
 * database means" (the {@link JdbcConnection} subclass). A configuration or test can replace it
 * without touching the connection class.
 */
@FunctionalInterface
public interface ConnectionFactory {

    java.sql.Connection connect(JdbcConnectionConfig config) throws SQLException;
}
