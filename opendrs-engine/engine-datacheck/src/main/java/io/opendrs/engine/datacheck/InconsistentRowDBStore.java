package io.opendrs.engine.datacheck;

import io.opendrs.engine.common.metadata.TableId;
import io.opendrs.engine.jdbc.JdbcConnection;
import io.opendrs.engine.jdbc.JdbcException;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * MySQL-backed {@link InconsistentRowStore}. Each data check (identified by its task id) owns one
 * result table {@code datacheck_result_<taskId>}; the table is created on first use and cleaned up
 * by the service.
 */
public final class InconsistentRowDBStore implements InconsistentRowStore {

    private static final int BATCH_ROWS = 500;

    private final Supplier<JdbcConnection> connections;
    private final boolean closeAfterUse;
    private final java.lang.String tableName;

    public InconsistentRowDBStore(JdbcConnection connection, long taskId) {
        this(() -> connection, taskId, false);
    }

    /**
     * Opens a short-lived metadata connection per operation so the task does not hold a
     * metadata-DB connection for its lifetime.
     */
    public InconsistentRowDBStore(Supplier<JdbcConnection> connections, long taskId) {
        this(connections, taskId, true);
    }

    private InconsistentRowDBStore(
            Supplier<JdbcConnection> connections, long taskId, boolean closeAfterUse) {
        this.connections = Objects.requireNonNull(connections, "connections");
        this.closeAfterUse = closeAfterUse;
        if (taskId <= 0) {
            throw new IllegalArgumentException("taskId must be positive");
        }
        this.tableName = "datacheck_result_" + taskId;
        createTable();
    }

    private JdbcConnection open() {
        JdbcConnection connection = connections.get();
        if (connection == null) {
            throw new IllegalStateException("metadata connection supplier returned null");
        }
        return connection;
    }

    private void release(JdbcConnection connection) {
        if (closeAfterUse && connection != null) {
            try {
                connection.close();
            } catch (Exception ignored) {
                // pool-managed or already closed
            }
        }
    }

    @Override
    public void markInconsistent(TableId table, List<java.lang.String> pks, int flag) {
        if (pks == null || pks.isEmpty()) {
            return;
        }
        java.lang.String tableId = table.identifier();
        JdbcConnection connection = open();
        try {
        for (int start = 0; start < pks.size(); start += BATCH_ROWS) {
            List<java.lang.String> chunk =
                    pks.subList(start, Math.min(start + BATCH_ROWS, pks.size()));
            try (PreparedStatement statement = connection.connection()
                    .prepareStatement(upsertSql(chunk.size()))) {
                int index = 1;
                for (java.lang.String pk : chunk) {
                    statement.setString(index++, tableId);
                    statement.setString(index++, pk);
                    statement.setInt(index++, flag);
                }
                statement.executeUpdate();
            } catch (SQLException ex) {
                throw new JdbcException("Failed to mark inconsistent rows of " + tableId, ex);
            }
        }
        } finally {
            release(connection);
        }
    }

    @Override
    public void markConsistent(TableId table, List<java.lang.String> pks) {
        if (pks == null || pks.isEmpty()) {
            return;
        }
        java.lang.String tableId = table.identifier();
        JdbcConnection connection = open();
        try {
        for (int start = 0; start < pks.size(); start += BATCH_ROWS) {
            List<java.lang.String> chunk =
                    pks.subList(start, Math.min(start + BATCH_ROWS, pks.size()));
            try (PreparedStatement statement = connection.connection()
                    .prepareStatement(deleteSql(chunk.size()))) {
                statement.setString(1, tableId);
                for (int i = 0; i < chunk.size(); i++) {
                    statement.setString(i + 2, chunk.get(i));
                }
                statement.executeUpdate();
            } catch (SQLException ex) {
                throw new JdbcException("Failed to clear consistent rows of " + tableId, ex);
            }
        }
        } finally {
            release(connection);
        }
    }

    @Override
    public List<java.lang.String> listInconsistent(TableId table) {
        JdbcConnection connection = open();
        try {
            return connection.queryList(
                    "SELECT pk FROM " + tableName + " WHERE table_id = ? ORDER BY pk",
                    rs -> rs.getString(1),
                    table.identifier());
        } catch (SQLException ex) {
            throw new JdbcException("Failed to list inconsistent rows of " + table.identifier(), ex);
        } finally {
            release(connection);
        }
    }

    @Override
    public boolean isInconsistent(TableId table, java.lang.String pk) {
        JdbcConnection connection = open();
        try {
            Integer found = connection.queryOne(
                    "SELECT 1 FROM " + tableName + " WHERE table_id = ? AND pk = ? LIMIT 1",
                    rs -> rs.getInt(1),
                    table.identifier(),
                    pk);
            return found != null;
        } catch (SQLException ex) {
            throw new JdbcException("Failed to test inconsistent row of " + table.identifier(), ex);
        } finally {
            release(connection);
        }
    }

    private void createTable() {
        JdbcConnection connection = open();
        try {
            connection.execute("""
                    CREATE TABLE IF NOT EXISTS %s (
                      table_id   varchar(128) NOT NULL,
                      pk         varchar(512) NOT NULL,
                      flag       int NOT NULL,
                      created_at timestamp DEFAULT CURRENT_TIMESTAMP,
                      updated_at timestamp DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                      PRIMARY KEY (table_id, pk)
                    )""".formatted(tableName));
        } catch (SQLException ex) {
            throw new JdbcException("Failed to create " + tableName, ex);
        } finally {
            release(connection);
        }
    }

    private java.lang.String upsertSql(int rows) {
        java.lang.StringBuilder sql = new java.lang.StringBuilder("INSERT INTO ")
                .append(tableName)
                .append(" (table_id, pk, flag) VALUES ");
        for (int i = 0; i < rows; i++) {
            if (i > 0) {
                sql.append(',');
            }
            sql.append("(?,?,?)");
        }
        return sql.append(" ON DUPLICATE KEY UPDATE flag = VALUES(flag)").toString();
    }

    private java.lang.String deleteSql(int rows) {
        java.lang.StringBuilder sql = new java.lang.StringBuilder("DELETE FROM ")
                .append(tableName)
                .append(" WHERE table_id = ? AND pk IN (");
        for (int i = 0; i < rows; i++) {
            if (i > 0) {
                sql.append(',');
            }
            sql.append('?');
        }
        return sql.append(')').toString();
    }
}
