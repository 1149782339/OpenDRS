package io.opendrs.engine.capture;

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
 * Kafka Connect {@code OffsetBackingStore} scoped by pipeline id. Upserts
 * {@code debezium_offsets} by {@code (pipeline_id, offset_key)} and never issues
 * {@code DELETE FROM} against the whole table (unlike official {@code JdbcOffsetBackingStore}).
 *
 * <p>Public no-arg constructor: Engine instantiates this class from {@code offset.storage}.
 */
public class TaskOffsetBackingStore extends MemoryOffsetBackingStore {

    public static final String TASK_ID_CONFIG = "offset.storage.opendrs.task.id";
    public static final String PIPELINE_ID_CONFIG = "offset.storage.opendrs.pipeline";
    /**
     * Registry key of the metadata DataSource; the engine name by default. Debezium instantiates
     * this store per engine, so the key must be per-engine, not per-task.
     */
    public static final String ENGINE_KEY_CONFIG = "offset.storage.opendrs.engine";

    private static final Logger log = LoggerFactory.getLogger(TaskOffsetBackingStore.class);

    private static final String SELECT_SQL =
            "SELECT offset_key, offset_val FROM debezium_offsets WHERE pipeline_id = ?";
    private static final String UPDATE_SQL =
            "UPDATE debezium_offsets SET offset_val = ?, updated_at = CURRENT_TIMESTAMP "
                    + "WHERE pipeline_id = ? AND offset_key = ?";
    private static final String INSERT_SQL =
            "INSERT INTO debezium_offsets (pipeline_id, offset_key, offset_val) VALUES (?, ?, ?)";
    private static final String DELETE_KEY_SQL =
            "DELETE FROM debezium_offsets WHERE pipeline_id = ? AND offset_key = ?";

    private Long taskId;
    private Long pipelineId;
    private String engineKey;
    private DataSource explicitDataSource;

    public TaskOffsetBackingStore() {
    }

    /**
     * Binds the metadata DataSource directly (tests, or callers that build the store themselves).
     * Debezium-instantiated stores fall back to the engine-scoped {@link TaskDataSourceRegistry}.
     */
    public TaskOffsetBackingStore withDataSource(DataSource dataSource) {
        this.explicitDataSource = dataSource;
        return this;
    }

    private DataSource dataSource() {
        if (explicitDataSource != null) {
            return explicitDataSource;
        }
        if (engineKey != null) {
            return TaskDataSourceRegistry.get(engineKey);
        }
        throw new IllegalStateException(ENGINE_KEY_CONFIG + " is required");
    }

    @Override
    public void configure(WorkerConfig config) {
        super.configure(config);
        configure(config.originals());
    }

    /**
     * Test helper so unit tests can skip a full Kafka {@link WorkerConfig}.
     */
    public void configure(Map<String, ?> configs) {
        Object rawPipeline = configs.get(PIPELINE_ID_CONFIG);
        if (rawPipeline == null || String.valueOf(rawPipeline).isBlank()) {
            throw new IllegalArgumentException(PIPELINE_ID_CONFIG + " is required");
        }
        this.pipelineId = Long.parseLong(String.valueOf(rawPipeline));
        Object rawTask = configs.get(TASK_ID_CONFIG);
        this.taskId = rawTask == null || String.valueOf(rawTask).isBlank()
                ? null
                : Long.parseLong(String.valueOf(rawTask));
        Object rawKey = configs.get(ENGINE_KEY_CONFIG);
        this.engineKey = rawKey == null || String.valueOf(rawKey).isBlank()
                ? null
                : String.valueOf(rawKey);
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
        DataSource dataSource = dataSource();
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
            throw new IllegalStateException("Failed to persist offsets for pipeline " + pipelineId, ex);
        }
    }

    @Override
    public Set<Map<String, Object>> connectorPartitions(String connectorName) {
        return Set.of();
    }

    Long pipelineId() {
        return pipelineId;
    }

    private void load() {
        data.clear();
        DataSource dataSource = dataSource();
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(SELECT_SQL)) {
            statement.setLong(1, pipelineId);
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    String key = rs.getString(1);
                    String value = rs.getString(2);
                    if (key != null) {
                        data.put(ByteBuffers.fromUtf8(key), ByteBuffers.fromUtf8(value));
                    }
                }
            }
            log.debug("Loaded {} offset row(s) for pipeline {}", data.size(), pipelineId);
        } catch (SQLException ex) {
            throw new IllegalStateException("Failed to load offsets for pipeline " + pipelineId, ex);
        }
    }

    private void upsert(Connection connection, String key, String value) throws SQLException {
        try (PreparedStatement update = connection.prepareStatement(UPDATE_SQL)) {
            update.setString(1, value);
            update.setLong(2, pipelineId);
            update.setString(3, key);
            if (update.executeUpdate() > 0) {
                return;
            }
        }
        try (PreparedStatement insert = connection.prepareStatement(INSERT_SQL)) {
            insert.setLong(1, pipelineId);
            insert.setString(2, key);
            insert.setString(3, value);
            insert.executeUpdate();
        } catch (SQLException ex) {
            try (PreparedStatement update = connection.prepareStatement(UPDATE_SQL)) {
                update.setString(1, value);
                update.setLong(2, pipelineId);
                update.setString(3, key);
                if (update.executeUpdate() == 0) {
                    throw ex;
                }
            }
        }
    }

    private void deleteKey(Connection connection, String key) throws SQLException {
        try (PreparedStatement delete = connection.prepareStatement(DELETE_KEY_SQL)) {
            delete.setLong(1, pipelineId);
            delete.setString(2, key);
            delete.executeUpdate();
        }
    }

    /** Exposed for tests that assert in-memory contents after load/save. */
    Map<ByteBuffer, ByteBuffer> snapshot() {
        return new HashMap<>(data);
    }
}
