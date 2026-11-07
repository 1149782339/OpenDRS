package io.opendrs.engine.capture.transport;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import javax.sql.DataSource;

/**
 * JDBC store for {@code pipeline_applied_offsets}. Uses the metadata DataSource (same DB as
 * {@code debezium_offsets}).
 */
public final class JdbcAppliedOffsetStore implements AppliedOffsetStore {

    private static final String UPSERT_UPDATE =
            "UPDATE pipeline_applied_offsets SET source_offset_json = ?, kafka_topic = ?, "
                    + "kafka_partition = ?, kafka_offset = ?, updated_at = CURRENT_TIMESTAMP "
                    + "WHERE pipeline_id = ?";
    private static final String UPSERT_INSERT =
            "INSERT INTO pipeline_applied_offsets "
                    + "(pipeline_id, source_offset_json, kafka_topic, kafka_partition, kafka_offset) "
                    + "VALUES (?, ?, ?, ?, ?)";
    private static final String SELECT =
            "SELECT pipeline_id, source_offset_json, kafka_topic, kafka_partition, kafka_offset "
                    + "FROM pipeline_applied_offsets WHERE pipeline_id = ?";

    private final DataSource dataSource;

    public JdbcAppliedOffsetStore(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public void saveApplied(long pipelineId, String sourceOffsetJson, String kafkaTopic, int kafkaPartition,
            long kafkaOffset) {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                int updated;
                try (PreparedStatement update = connection.prepareStatement(UPSERT_UPDATE)) {
                    update.setString(1, sourceOffsetJson);
                    update.setString(2, kafkaTopic);
                    update.setInt(3, kafkaPartition);
                    update.setLong(4, kafkaOffset);
                    update.setLong(5, pipelineId);
                    updated = update.executeUpdate();
                }
                if (updated == 0) {
                    try (PreparedStatement insert = connection.prepareStatement(UPSERT_INSERT)) {
                        insert.setLong(1, pipelineId);
                        insert.setString(2, sourceOffsetJson);
                        insert.setString(3, kafkaTopic);
                        insert.setInt(4, kafkaPartition);
                        insert.setLong(5, kafkaOffset);
                        insert.executeUpdate();
                    }
                }
                connection.commit();
            }
            catch (SQLException ex) {
                connection.rollback();
                throw ex;
            }
        }
        catch (SQLException ex) {
            throw new IllegalStateException(
                    "Failed to save applied offset for pipeline " + pipelineId, ex);
        }
    }

    @Override
    public AppliedOffset find(long pipelineId) {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(SELECT)) {
            statement.setLong(1, pipelineId);
            try (ResultSet rs = statement.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                return new AppliedOffset(
                        rs.getLong("pipeline_id"),
                        rs.getString("source_offset_json"),
                        rs.getString("kafka_topic"),
                        (Integer) rs.getObject("kafka_partition"),
                        (Long) rs.getObject("kafka_offset"));
            }
        }
        catch (SQLException ex) {
            throw new IllegalStateException(
                    "Failed to load applied offset for pipeline " + pipelineId, ex);
        }
    }
}
