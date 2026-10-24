package io.opendrs.jdbc;

@FunctionalInterface
public interface RowMapper<T> {
    T map(java.sql.ResultSet rs) throws java.sql.SQLException;
}
