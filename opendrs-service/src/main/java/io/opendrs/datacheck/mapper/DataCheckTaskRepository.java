package io.opendrs.datacheck.mapper;

import io.opendrs.datacheck.api.response.DataCheckResultItem;
import io.opendrs.datacheck.domain.DataCheckMode;
import io.opendrs.datacheck.domain.DataCheckPhase;
import io.opendrs.datacheck.domain.DataCheckTableStatus;
import io.opendrs.engine.common.metadata.DatabaseType;
import io.opendrs.engine.core.TaskState;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.BadSqlGrammarException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

/**
 * All SQL for the data-check service. The result table name is dynamic
 * ({@code datacheck_result_<taskId>}); the id is a {@code long} taken from the task row, never
 * arbitrary text.
 */
@Repository
public class DataCheckTaskRepository {

    private final JdbcTemplate jdbc;

    public DataCheckTaskRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ------------------------------------------------------------------
    // Creation lookups
    // ------------------------------------------------------------------

    public MigrationTaskRow findMigrationTask(long id) {
        List<MigrationTaskRow> rows = jdbc.query(
                "SELECT id, source_endpoint_id, target_endpoint_id, options"
                        + " FROM migration_tasks WHERE id = ?",
                (rs, rowNum) -> new MigrationTaskRow(
                        rs.getLong("id"),
                        rs.getLong("source_endpoint_id"),
                        rs.getLong("target_endpoint_id"),
                        rs.getString("options")),
                id);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    public List<EndpointRow> findEndpoints(Collection<Long> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        String placeholders = String.join(",", java.util.Collections.nCopies(ids.size(), "?"));
        return jdbc.query(
                "SELECT id, type, host, port, db_name, username, password, options"
                        + " FROM endpoints WHERE id IN (" + placeholders + ")",
                (rs, rowNum) -> new EndpointRow(
                        rs.getLong("id"),
                        DatabaseType.valueOf(rs.getString("type").trim().toUpperCase(java.util.Locale.ROOT)),
                        rs.getString("host"),
                        rs.getInt("port"),
                        rs.getString("db_name"),
                        rs.getString("username"),
                        rs.getString("password"),
                        rs.getString("options")),
                ids.toArray());
    }

    public long insertTask(
            String publicId,
            long migrationTaskId,
            Long pipelineId,
            String name,
            DataCheckMode mode,
            String optionsJson) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            var statement = connection.prepareStatement(
                    "INSERT INTO datacheck_tasks"
                            + " (public_id, migration_task_id, pipeline_id, name, mode, state, options)"
                            + " VALUES (?, ?, ?, ?, ?, NULL, ?)",
                    java.sql.Statement.RETURN_GENERATED_KEYS);
            statement.setString(1, publicId);
            statement.setLong(2, migrationTaskId);
            if (pipelineId == null) {
                statement.setNull(3, java.sql.Types.BIGINT);
            } else {
                statement.setLong(3, pipelineId);
            }
            statement.setString(4, name);
            statement.setString(5, mode.name());
            statement.setString(6, optionsJson);
            return statement;
        }, keys);
        Number key = keys.getKey();
        if (key == null) {
            throw new IllegalStateException("datacheck_tasks insert did not return an id");
        }
        return key.longValue();
    }

    public void insertTableTask(long taskId, String sourceTableId, String targetTableId) {
        jdbc.update(
                "INSERT INTO datacheck_table_tasks"
                        + " (datacheck_task_id, source_table_id, target_table_id, phase, status)"
                        + " VALUES (?, ?, ?, ?, ?)",
                taskId, sourceTableId, targetTableId, DataCheckPhase.PENDING.name(),
                DataCheckTableStatus.PENDING.name());
    }

    // ------------------------------------------------------------------
    // Reads
    // ------------------------------------------------------------------

    public DataCheckTaskRow findTask(long id) {
        List<DataCheckTaskRow> rows = jdbc.query(
                "SELECT id, public_id, migration_task_id, pipeline_id, name, mode, state, options,"
                        + " lag_ms, error_message, created_at, updated_at"
                        + " FROM datacheck_tasks WHERE id = ?",
                (rs, rowNum) -> taskRow(rs),
                id);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    public List<DataCheckTaskRow> listTasks(Long migrationTaskId) {
        if (migrationTaskId == null) {
            return jdbc.query(
                    "SELECT id, public_id, migration_task_id, pipeline_id, name, mode, state, options,"
                            + " lag_ms, error_message, created_at, updated_at"
                            + " FROM datacheck_tasks ORDER BY id DESC",
                    (rs, rowNum) -> taskRow(rs));
        }
        return jdbc.query(
                "SELECT id, public_id, migration_task_id, pipeline_id, name, mode, state, options,"
                        + " lag_ms, error_message, created_at, updated_at"
                        + " FROM datacheck_tasks WHERE migration_task_id = ? ORDER BY id DESC",
                (rs, rowNum) -> taskRow(rs),
                migrationTaskId);
    }

    public List<DataCheckTableRow> findTableRows(long taskId) {
        return jdbc.query(
                "SELECT source_table_id, target_table_id, phase, status, lag_ms, error_message,"
                        + " started_at, finished_at"
                        + " FROM datacheck_table_tasks WHERE datacheck_task_id = ?"
                        + " ORDER BY source_table_id",
                (rs, rowNum) -> new DataCheckTableRow(
                        rs.getString("source_table_id"),
                        rs.getString("target_table_id"),
                        DataCheckPhase.valueOf(rs.getString("phase")),
                        DataCheckTableStatus.valueOf(rs.getString("status")),
                        longOrNull(rs, "lag_ms"),
                        rs.getString("error_message"),
                        instant(rs, "started_at"),
                        instant(rs, "finished_at")),
                taskId);
    }

    // ------------------------------------------------------------------
    // State updates
    // ------------------------------------------------------------------

    public int casState(long id, TaskState expected, TaskState next) {
        if (expected == null) {
            return jdbc.update(
                    "UPDATE datacheck_tasks SET state = ?, error_message = NULL,"
                            + " updated_at = CURRENT_TIMESTAMP WHERE id = ? AND state IS NULL",
                    next.name(), id);
        }
        return jdbc.update(
                "UPDATE datacheck_tasks SET state = ?, error_message = NULL,"
                        + " updated_at = CURRENT_TIMESTAMP WHERE id = ? AND state = ?",
                next.name(), id, expected.name());
    }

    public int landStopped(long id) {
        return jdbc.update(
                "UPDATE datacheck_tasks SET state = 'STOPPED', updated_at = CURRENT_TIMESTAMP"
                        + " WHERE id = ? AND state IN ('STOPPING', 'RUNNING')",
                id);
    }

    public int landTerminated(long id) {
        return jdbc.update(
                "UPDATE datacheck_tasks SET state = 'TERMINATED', updated_at = CURRENT_TIMESTAMP"
                        + " WHERE id = ? AND state IN ('TERMINATING', 'RUNNING')",
                id);
    }

    public int landCompleted(long id) {
        return jdbc.update(
                "UPDATE datacheck_tasks SET state = 'COMPLETED', updated_at = CURRENT_TIMESTAMP"
                        + " WHERE id = ? AND state = 'RUNNING'",
                id);
    }

    public void markFailed(long id, String message) {
        jdbc.update(
                "UPDATE datacheck_tasks SET state = 'FAILED', error_message = ?,"
                        + " updated_at = CURRENT_TIMESTAMP WHERE id = ?",
                message, id);
    }

    public void markTableRunning(long taskId, String sourceTableId) {
        jdbc.update(
                "UPDATE datacheck_table_tasks SET phase = 'FULL', status = 'RUNNING',"
                        + " error_message = NULL, started_at = COALESCE(started_at, CURRENT_TIMESTAMP),"
                        + " updated_at = CURRENT_TIMESTAMP"
                        + " WHERE datacheck_task_id = ? AND source_table_id = ?",
                taskId, sourceTableId);
    }

    public void markTablePhase(long taskId, String sourceTableId, DataCheckPhase phase) {
        jdbc.update(
                "UPDATE datacheck_table_tasks SET phase = ?, updated_at = CURRENT_TIMESTAMP"
                        + " WHERE datacheck_task_id = ? AND source_table_id = ?",
                phase.name(), taskId, sourceTableId);
    }

    public void markTableFinished(
            long taskId, String sourceTableId, DataCheckTableStatus status, String error) {
        jdbc.update(
                "UPDATE datacheck_table_tasks SET status = ?, error_message = ?,"
                        + " finished_at = CURRENT_TIMESTAMP, updated_at = CURRENT_TIMESTAMP"
                        + " WHERE datacheck_task_id = ? AND source_table_id = ?",
                status.name(), error, taskId, sourceTableId);
    }

    public void deleteTask(long id) {
        jdbc.update("DELETE FROM datacheck_table_tasks WHERE datacheck_task_id = ?", id);
        jdbc.update("DELETE FROM datacheck_tasks WHERE id = ?", id);
        dropResultTable(id);
    }

    public void dropResultTable(long taskId) {
        jdbc.execute("DROP TABLE IF EXISTS " + resultTable(taskId));
    }

    // ------------------------------------------------------------------
    // Result queries
    // ------------------------------------------------------------------

    public long countResults(long taskId, String tableId, Integer flag) {
        StringBuilder sql = new StringBuilder("SELECT COUNT(*) FROM ")
                .append(resultTable(taskId))
                .append(" WHERE 1 = 1");
        List<Object> args = new ArrayList<>();
        appendFilters(sql, args, tableId, flag);
        try {
            Long count = jdbc.queryForObject(sql.toString(), Long.class, args.toArray());
            return count == null ? 0 : count;
        } catch (BadSqlGrammarException ex) {
            return 0;
        }
    }

    public List<DataCheckResultItem> pageResults(
            long taskId, String tableId, Integer flag, int page, int size) {
        StringBuilder sql = new StringBuilder(
                "SELECT table_id, pk, flag, created_at, updated_at FROM ")
                .append(resultTable(taskId))
                .append(" WHERE 1 = 1");
        List<Object> args = new ArrayList<>();
        appendFilters(sql, args, tableId, flag);
        sql.append(" ORDER BY table_id, pk LIMIT ? OFFSET ?");
        args.add(size);
        args.add((long) (page - 1) * size);
        try {
            return jdbc.query(sql.toString(), (rs, rowNum) -> new DataCheckResultItem(
                    rs.getString("table_id"),
                    rs.getString("pk"),
                    rs.getInt("flag"),
                    instant(rs, "created_at"),
                    instant(rs, "updated_at")), args.toArray());
        } catch (BadSqlGrammarException ex) {
            return List.of();
        }
    }

    /** tableId -> [different, sourceOnly, targetOnly]. */
    public Map<String, long[]> countsByTable(long taskId) {
        Map<String, long[]> counts = new HashMap<>();
        try {
            jdbc.query("SELECT table_id, flag, COUNT(*) AS c FROM " + resultTable(taskId)
                    + " GROUP BY table_id, flag", rs -> {
                        while (rs.next()) {
                            long[] perFlag = counts.computeIfAbsent(
                                    rs.getString("table_id"), key -> new long[3]);
                            int flag = rs.getInt("flag");
                            if (flag >= 0 && flag < 3) {
                                perFlag[flag] = rs.getLong("c");
                            }
                        }
                        return counts;
                    });
        } catch (BadSqlGrammarException ex) {
            return Map.of();
        }
        return counts;
    }

    private static void appendFilters(
            StringBuilder sql, List<Object> args, String tableId, Integer flag) {
        if (tableId != null && !tableId.isBlank()) {
            sql.append(" AND table_id = ?");
            args.add(tableId);
        }
        if (flag != null) {
            sql.append(" AND flag = ?");
            args.add(flag);
        }
    }

    private static String resultTable(long taskId) {
        if (taskId <= 0) {
            throw new IllegalArgumentException("taskId must be positive");
        }
        return "datacheck_result_" + taskId;
    }

    // ------------------------------------------------------------------
    // Row mapping
    // ------------------------------------------------------------------

    private static DataCheckTaskRow taskRow(ResultSet rs) throws SQLException {
        String state = rs.getString("state");
        return new DataCheckTaskRow(
                rs.getLong("id"),
                rs.getString("public_id"),
                rs.getLong("migration_task_id"),
                longOrNull(rs, "pipeline_id"),
                rs.getString("name"),
                DataCheckMode.valueOf(rs.getString("mode")),
                state == null ? null : TaskState.valueOf(state),
                rs.getString("options"),
                longOrNull(rs, "lag_ms"),
                rs.getString("error_message"),
                instant(rs, "created_at"),
                instant(rs, "updated_at"));
    }

    private static Long longOrNull(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp timestamp = rs.getTimestamp(column);
        return timestamp == null ? null : timestamp.toInstant();
    }

    public record MigrationTaskRow(
            long id, long sourceEndpointId, long targetEndpointId, String optionsJson) {
    }

    public record EndpointRow(
            long id,
            DatabaseType type,
            String host,
            int port,
            String database,
            String username,
            String password,
            String optionsJson) {
    }

    public record DataCheckTaskRow(
            long id,
            String publicId,
            long migrationTaskId,
            Long pipelineId,
            String name,
            DataCheckMode mode,
            TaskState state,
            String optionsJson,
            Long lagMs,
            String errorMessage,
            Instant createdAt,
            Instant updatedAt) {
    }

    public record DataCheckTableRow(
            String sourceTableId,
            String targetTableId,
            DataCheckPhase phase,
            DataCheckTableStatus status,
            Long lagMs,
            String errorMessage,
            Instant startedAt,
            Instant finishedAt) {
    }
}
