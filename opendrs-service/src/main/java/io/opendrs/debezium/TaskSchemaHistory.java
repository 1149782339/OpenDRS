package io.opendrs.debezium;

import io.debezium.config.Configuration;
import io.debezium.document.DocumentReader;
import io.debezium.document.DocumentWriter;
import io.debezium.relational.history.AbstractSchemaHistory;
import io.debezium.relational.history.HistoryRecord;
import io.debezium.relational.history.HistoryRecordComparator;
import io.debezium.relational.history.SchemaHistory;
import io.debezium.relational.history.SchemaHistoryException;
import io.debezium.relational.history.SchemaHistoryListener;
import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import javax.sql.DataSource;

/**
 * Debezium 3.x {@link SchemaHistory} that appends {@link HistoryRecord} documents to
 * {@code debezium_schema_history} scoped by {@code task_id}. Does not hand-write HistoryRecord JSON:
 * serialization goes through {@link DocumentWriter} / {@link HistoryRecord#document()}.
 *
 * <p>Public no-arg constructor: Engine instantiates this class from {@code schema.history.internal}.
 */
public class TaskSchemaHistory extends AbstractSchemaHistory {

    public static final String TASK_ID_CONFIG = SchemaHistory.CONFIGURATION_FIELD_PREFIX_STRING + "opendrs.task.id";

    private static final String SELECT_SQL =
            "SELECT history_data FROM debezium_schema_history WHERE task_id = ? ORDER BY record_seq";
    private static final String NEXT_SEQ_SQL =
            "SELECT COALESCE(MAX(record_seq), 0) FROM debezium_schema_history WHERE task_id = ?";
    private static final String INSERT_SQL =
            "INSERT INTO debezium_schema_history (task_id, record_seq, history_data) VALUES (?, ?, ?)";
    private static final String STORAGE_PROBE_SQL = "SELECT 1 FROM debezium_schema_history WHERE 1 = 0";

    private final DocumentWriter documentWriter = DocumentWriter.defaultWriter();
    private final DocumentReader documentReader = DocumentReader.defaultReader();

    private Long taskId;

    public TaskSchemaHistory() {
    }

    @Override
    public void configure(
            Configuration config,
            HistoryRecordComparator comparator,
            SchemaHistoryListener listener,
            boolean useCatalogBeforeSchema) {
        super.configure(config, comparator, listener, useCatalogBeforeSchema);
        String raw = config.getString(TASK_ID_CONFIG);
        if (raw == null || raw.isBlank()) {
            raw = config.getString("opendrs.task.id");
        }
        if (raw == null || raw.isBlank()) {
            throw new SchemaHistoryException(TASK_ID_CONFIG + " is required");
        }
        this.taskId = Long.parseLong(raw);
    }

    @Override
    protected void storeRecord(HistoryRecord record) throws SchemaHistoryException {
        if (record == null) {
            return;
        }
        String json;
        try {
            json = documentWriter.write(record.document());
        } catch (IOException ex) {
            throw new SchemaHistoryException("Failed to serialize schema history record", ex);
        }
        DataSource dataSource = EngineDataSourceHolder.get();
        try (Connection connection = dataSource.getConnection()) {
            int nextSeq = nextSeq(connection);
            try (PreparedStatement insert = connection.prepareStatement(INSERT_SQL)) {
                insert.setLong(1, taskId);
                insert.setInt(2, nextSeq);
                insert.setString(3, json);
                insert.executeUpdate();
            }
        } catch (SQLException ex) {
            throw new SchemaHistoryException("Failed to append schema history for task " + taskId, ex);
        }
    }

    @Override
    protected void recoverRecords(Consumer<HistoryRecord> records) {
        for (HistoryRecord record : loadRecords()) {
            records.accept(record);
        }
    }

    @Override
    public boolean exists() {
        return !loadRecords().isEmpty();
    }

    @Override
    public boolean storageExists() {
        DataSource dataSource = EngineDataSourceHolder.get();
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(STORAGE_PROBE_SQL);
                ResultSet rs = statement.executeQuery()) {
            return true;
        } catch (SQLException ex) {
            return false;
        }
    }

    Long taskId() {
        return taskId;
    }

    private int nextSeq(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(NEXT_SEQ_SQL)) {
            statement.setLong(1, taskId);
            try (ResultSet rs = statement.executeQuery()) {
                if (rs.next()) {
                    return rs.getInt(1) + 1;
                }
                return 1;
            }
        }
    }

    private List<HistoryRecord> loadRecords() {
        List<HistoryRecord> records = new ArrayList<>();
        DataSource dataSource = EngineDataSourceHolder.get();
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(SELECT_SQL)) {
            statement.setLong(1, taskId);
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    String json = rs.getString(1);
                    if (json == null || json.isBlank()) {
                        continue;
                    }
                    records.add(new HistoryRecord(documentReader.read(json)));
                }
            }
        } catch (SQLException | IOException ex) {
            throw new SchemaHistoryException("Failed to recover schema history for task " + taskId, ex);
        }
        return records;
    }
}
