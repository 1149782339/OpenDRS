package io.opendrs.jdbc;

public interface JdbcConnection extends AutoCloseable {

    static JdbcConnection open(io.opendrs.migration.domain.ConnectionInfo info) {
        return JdbcConnections.open(info);
    }

    io.opendrs.migration.domain.DbType type();

    /** TCP + auth + optional PDB. Throw AppException, never raw SQLException. */
    void ping();

    /** Caller must not close the raw connection. */
    java.sql.Connection unwrap();

    <T> T queryOne(String sql, RowMapper<T> mapper);

    void execute(String sql);

    @Override
    void close();
}
