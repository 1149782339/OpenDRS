package io.opendrs.debezium;

import java.nio.ByteBuffer;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.apache.kafka.common.utils.ThreadUtils;
import org.apache.kafka.connect.runtime.WorkerConfig;
import org.apache.kafka.connect.storage.MemoryOffsetBackingStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Kafka Connect {@code OffsetBackingStore} scoped by {@code task_id}. Upserts
 * {@code debezium_offset} by {@code (task_id, offset_key)} and never issues {@code DELETE FROM}
 * against the whole table (unlike official {@code JdbcOffsetBackingStore}).
 *
 * <p>Public no-arg constructor: Engine instantiates this class from {@code offset.storage}.
 */
public class TaskOffsetBackingStore extends MemoryOffsetBackingStore {

    public static final String TASK_ID_CONFIG = "offset.storage.opendrs.task.id";

    private static final Logger log = LoggerFactory.getLogger(TaskOffsetBackingStore.class);

    private static final String SELECT_SQL =
            "SELECT offset_key, offset_val FROM debezium_offset WHERE task_id = ?";
    private static final String UPDATE_SQL =
            "UPDATE debezium_offset SET offset_val = ?, updated_at = CURRENT_TIMESTAMP "
                    + "WHERE task_id = ? AND offset_key = ?";
    private static final String INSERT_SQL =
            "INSERT INTO debezium_offset (task_id, offset_key, offset_val) VALUES (?, ?, ?)";
    private static final String DELETE_KEY_SQL =
            "DELETE FROM debezium_offset WHERE task_id = ? AND offset_key = ?";

    private Long taskId;

    public TaskOffsetBackingStore() {
    }

    @Override
    public void configure(WorkerConfig config) {
        super.configure(config);
        this.taskId = parseTaskId(config.originals());
    }

    /**
     * Test helper so unit tests can skip a full Kafka {@link WorkerConfig}.
     */
    public void configure(Map<String, ?> configs) {
        this.taskId = parseTaskId(configs);
    }

    @Override
    public synchronized void start() {
        super.start();
        load();
    }

    @Override
    public synchronized void stop() {
        if (executor != null) {
            ThreadUtils.shutdownExecutorServiceQuietly(executor, 2, TimeUnit.SECONDS);
            executor = null;
        }
    }

    @Override
    protected void save() {
        if (taskId == null) {
            throw new IllegalStateException(TASK_ID_CONFIG + " is required");
        }
        DataSource dataSource = EngineDataSourceHolder.get();
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                for (Map.Entry<ByteBuffer, ByteBuffer> entry : data.entrySet()) {
                    String key = ByteBuffers.toUtf8(entry.getKey());
                    if (entry.getValue() == null) {
                        deleteKey(connection, key);
                    } else {
                        upsert(connection, key, ByteBuffers.toUtf8(entry.getValue()));
                    }
                }
                connection.commit();
            } catch (SQLException ex) {
                connection.rollback();
                throw ex;
            }
        } catch (SQLException ex) {
            throw new IllegalStateException("Failed to persist offsets for task " + taskId, ex);
        }
    }

    @Override
    public Set<Map<String, Object>> connectorPartitions(String connectorName) {
        return Set.of();
    }

    Long taskId() {
        return taskId;
    }

    private void load() {
        data.clear();
        DataSource dataSource = EngineDataSourceHolder.get();
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(SELECT_SQL)) {
            statement.setLong(1, taskId);
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    String key = rs.getString(1);
                    String value = rs.getString(2);
                    if (key != null) {
                        data.put(ByteBuffers.fromUtf8(key), ByteBuffers.fromUtf8(value));
                    }
                }
            }
            log.debug("Loaded {} offset row(s) for task {}", data.size(), taskId);
        } catch (SQLException ex) {
            throw new IllegalStateException("Failed to load offsets for task " + taskId, ex);
        }
    }

    private void upsert(Connection connection, String key, String value) throws SQLException {
        try (PreparedStatement update = connection.prepareStatement(UPDATE_SQL)) {
            update.setString(1, value);
            update.setLong(2, taskId);
            update.setString(3, key);
            if (update.executeUpdate() > 0) {
                return;
            }
        }
        try (PreparedStatement insert = connection.prepareStatement(INSERT_SQL)) {
            insert.setLong(1, taskId);
            insert.setString(2, key);
            insert.setString(3, value);
            insert.executeUpdate();
        } catch (SQLException ex) {
            try (PreparedStatement update = connection.prepareStatement(UPDATE_SQL)) {
                update.setString(1, value);
                update.setLong(2, taskId);
                update.setString(3, key);
                if (update.executeUpdate() == 0) {
                    throw ex;
                }
            }
        }
    }

    private void deleteKey(Connection connection, String key) throws SQLException {
        try (PreparedStatement delete = connection.prepareStatement(DELETE_KEY_SQL)) {
            delete.setLong(1, taskId);
            delete.setString(2, key);
            delete.executeUpdate();
        }
    }

    static Long parseTaskId(Map<String, ?> configs) {
        if (configs == null) {
            throw new IllegalArgumentException(TASK_ID_CONFIG + " is required");
        }
        Object raw = configs.get(TASK_ID_CONFIG);
        if (raw == null) {
            Object prefixed = configs.get("opendrs.task.id");
            raw = prefixed;
        }
        if (raw == null || String.valueOf(raw).isBlank()) {
            throw new IllegalArgumentException(TASK_ID_CONFIG + " is required");
        }
        return Long.parseLong(String.valueOf(raw));
    }

    /** Exposed for tests that assert in-memory contents after load/save. */
    Map<ByteBuffer, ByteBuffer> snapshot() {
        return new HashMap<>(data);
    }
}
