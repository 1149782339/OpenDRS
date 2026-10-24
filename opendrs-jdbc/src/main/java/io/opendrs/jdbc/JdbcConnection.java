package io.opendrs.jdbc;

import io.opendrs.common.metadata.DatabaseType;

public interface JdbcConnection extends AutoCloseable {

    DatabaseType type();

    /** TCP + auth + optional PDB. Throws JdbcException, never raw SQLException. */
    void ping();

    /** Caller must not close the raw connection. */
    java.sql.Connection unwrap();

    <T> T queryOne(String sql, RowMapper<T> mapper);

    /** Single row with bound parameters; {@code null} when the query returns nothing. */
    default <T> T queryOne(String sql, RowMapper<T> mapper, Object... params) {
        java.util.List<T> rows = queryList(sql, mapper, params);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    <T> java.util.List<T> queryList(String sql, RowMapper<T> mapper);

    <T> java.util.List<T> queryList(String sql, RowMapper<T> mapper, Object... params);

    void execute(String sql);

    @Override
    void close();
}
