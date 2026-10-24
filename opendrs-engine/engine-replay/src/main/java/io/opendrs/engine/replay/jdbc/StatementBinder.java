package io.opendrs.engine.replay.jdbc;


import io.opendrs.engine.replay.event.DataChangeEvent;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;

/**
 * statement binder defines the interface for DML statement for binding parameters
 *
 */
public interface StatementBinder {

    /**
     * Bind parameters for insert statement
     *
     * @param statement {@link PreparedStatement}
     * @param event     {@link DataChangeEvent}
     * @return the equivalent java sql Timestamp {@link Timestamp}
     * @throws SQLException if there is an error using JDBC interface
     */
    void bindInsertStatement(PreparedStatement statement, DataChangeEvent event) throws SQLException;

    /**
     * Bind parameters for update statement
     *
     * @param statement {@link PreparedStatement}
     * @param event     {@link DataChangeEvent}
     * @return the equivalent java sql Timestamp {@link Timestamp}
     * @throws SQLException if there is an error using JDBC interface
     */
    void bindUpdateStatement(PreparedStatement statement, DataChangeEvent event) throws SQLException;

    /**
     * Bind parameters for delete statement
     *
     * @param statement {@link PreparedStatement}
     * @param event     {@link DataChangeEvent}
     * @return the equivalent java sql Timestamp {@link Timestamp}
     * @throws SQLException if there is an error using JDBC interface
     */
    void bindDeleteStatement(PreparedStatement statement, DataChangeEvent event) throws SQLException;

    /**
     * Bind parameters for upsert statement
     *
     * @param statement {@link PreparedStatement}
     * @param event     {@link DataChangeEvent}
     * @return the equivalent java sql Timestamp {@link Timestamp}
     * @throws SQLException if there is an error using JDBC interface
     */
    void bindUpsertStatement(PreparedStatement statement, DataChangeEvent event) throws SQLException;
}
