package io.opendrs.jdbc;

public interface JdbcConnection extends AutoCloseable {

    static JdbcConnection open(io.opendrs.migration.domain.ConnectionInfo info) {
        return new JdbcConnectionFactory().open(info);
    }

    io.opendrs.migration.domain.DbType type();

    /** TCP + auth + optional PDB. Throw AppException, never raw SQLException. */
    void ping();

    /** Caller must not close the raw connection. */
    java.sql.Connection unwrap();

    <T> T queryOne(String sql, RowMapper<T> mapper);

    <T> java.util.List<T> queryList(String sql, RowMapper<T> mapper);

    <T> java.util.List<T> queryList(String sql, RowMapper<T> mapper, Object... params);

    void execute(String sql);

    @Override
    void close();
}
