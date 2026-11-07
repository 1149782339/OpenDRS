package io.opendrs.engine.capture.transport;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.apache.kafka.connect.data.Schema;
import org.apache.kafka.connect.data.SchemaBuilder;
import org.apache.kafka.connect.data.Struct;
import org.apache.kafka.connect.source.SourceRecord;
import org.junit.jupiter.api.Test;

class KafkaProduceChangeConsumerTest {

    private static final Schema SOURCE_SCHEMA = SchemaBuilder.struct()
            .field("snapshot", Schema.OPTIONAL_STRING_SCHEMA)
            .field("gtid", Schema.OPTIONAL_STRING_SCHEMA)
            .build();

    private static final Schema ENVELOPE = SchemaBuilder.struct()
            .name("test.Envelope")
            .field("op", Schema.STRING_SCHEMA)
            .field("source", SOURCE_SCHEMA)
            .build();

    @Test
    void snapshotMarkerRoutesAsSnapshot() {
        assertThat(KafkaProduceChangeConsumer.isSnapshotRecord(record("r", "true"))).isTrue();
        assertThat(KafkaProduceChangeConsumer.isSnapshotRecord(record("r", "first"))).isTrue();
        assertThat(KafkaProduceChangeConsumer.isSnapshotRecord(record("r", "last"))).isTrue();
    }

    @Test
    void readOpWithoutMarkerStillSnapshot() {
        assertThat(KafkaProduceChangeConsumer.isSnapshotRecord(record("r", null))).isTrue();
    }

    @Test
    void streamingOpsBypassSnapshot() {
        assertThat(KafkaProduceChangeConsumer.isSnapshotRecord(record("c", null))).isFalse();
        assertThat(KafkaProduceChangeConsumer.isSnapshotRecord(record("u", null))).isFalse();
        assertThat(KafkaProduceChangeConsumer.isSnapshotRecord(record("d", null))).isFalse();
        assertThat(KafkaProduceChangeConsumer.isSnapshotRecord(record("c", "false"))).isFalse();
    }

    @Test
    void nullRecordIsNotSnapshot() {
        assertThat(KafkaProduceChangeConsumer.isSnapshotRecord(null)).isFalse();
    }


    private static final Schema NOTIFICATION_SCHEMA = SchemaBuilder.struct()
            .field("aggregate_type", Schema.STRING_SCHEMA)
            .field("type", Schema.STRING_SCHEMA)
            .field("additional_data", SchemaBuilder.map(Schema.STRING_SCHEMA, Schema.STRING_SCHEMA))
            .build();

    @Test
    void initialSnapshotNotificationsBypassKafka() {
        assertThat(KafkaProduceChangeConsumer.shouldBypassKafka(
                notification("TABLE_SCAN_COMPLETED", Map.of(
                        "scanned_collection", "hr.emp",
                        "total_rows_scanned", "7",
                        "status", "SUCCEEDED")))).isTrue();
        assertThat(KafkaProduceChangeConsumer.shouldBypassKafka(
                notification("DATA_COLLECTIONS_RESOLVED",
                        Map.of("data_collections", "hr.dept,hr.emp")))).isTrue();
        assertThat(KafkaProduceChangeConsumer.shouldBypassKafka(
                notification("COMPLETED", Map.of()))).isTrue();
        // Notifications have no op/source.snapshot — isSnapshotRecord alone misses them.
        assertThat(KafkaProduceChangeConsumer.isSnapshotRecord(
                notification("TABLE_SCAN_COMPLETED", Map.of(
                        "scanned_collection", "hr.emp",
                        "status", "SUCCEEDED")))).isFalse();
    }

    @Test
    void incrementalSnapshotNotificationsStayOnStreamingPath() {
        SourceRecord record = notification("TABLE_SCAN_COMPLETED", Map.of(
                "scanned_collection", "hr.emp", "status", "SUCCEEDED"));
        ((Struct) record.value()).put("aggregate_type", "Incremental Snapshot");
        assertThat(KafkaProduceChangeConsumer.shouldBypassKafka(record)).isFalse();
    }

    @Test
    void streamingDataDoesNotBypass() {
        assertThat(KafkaProduceChangeConsumer.shouldBypassKafka(record("c", null))).isFalse();
        assertThat(KafkaProduceChangeConsumer.shouldBypassKafka(record("u", null))).isFalse();
    }

    private static SourceRecord notification(String type, Map<String, String> additionalData) {
        Struct value = new Struct(NOTIFICATION_SCHEMA)
                .put("aggregate_type", "Initial Snapshot")
                .put("type", type)
                .put("additional_data", additionalData);
        return new SourceRecord(
                null, null, "server.notification", null,
                null, null, NOTIFICATION_SCHEMA, value);
    }

    private static SourceRecord record(String op, String snapshotMarker) {
        Struct source = new Struct(SOURCE_SCHEMA);
        if (snapshotMarker != null) {
            source.put("snapshot", snapshotMarker);
        }
        Struct value = new Struct(ENVELOPE).put("op", op).put("source", source);
        return new SourceRecord(
                null, null, "server.db.t", null,
                Schema.STRING_SCHEMA, "k",
                ENVELOPE, value);
    }
}
