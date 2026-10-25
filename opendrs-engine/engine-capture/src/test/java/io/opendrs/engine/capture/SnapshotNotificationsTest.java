package io.opendrs.engine.capture;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.apache.kafka.connect.data.Schema;
import org.apache.kafka.connect.data.SchemaBuilder;
import org.apache.kafka.connect.data.Struct;
import org.apache.kafka.connect.source.SourceRecord;
import org.junit.jupiter.api.Test;

class SnapshotNotificationsTest {

    private static final Schema NOTIFICATION_SCHEMA = SchemaBuilder.struct()
            .field("aggregate_type", Schema.STRING_SCHEMA)
            .field("type", Schema.STRING_SCHEMA)
            .field("additional_data", SchemaBuilder.map(Schema.STRING_SCHEMA, Schema.STRING_SCHEMA))
            .build();

    @Test
    void parsesSucceededTableScanCompleted() {
        SourceRecord record = notification(
                "TABLE_SCAN_COMPLETED",
                Map.of(
                        "scanned_collection", "hr.emp",
                        "total_rows_scanned", "42",
                        "status", "SUCCEEDED",
                        "connector_name", "opendrs.pipeline.7"));

        Optional<SnapshotNotifications.TableScanCompleted> parsed =
                SnapshotNotifications.parseTableScanCompleted(record);

        assertThat(parsed).isPresent();
        assertThat(parsed.get().table()).isEqualTo("hr.emp");
        assertThat(parsed.get().totalRows()).isEqualTo(42);
        assertThat(parsed.get().succeeded()).isTrue();
    }

    @Test
    void failedScanParsesWithSucceededFalse() {
        SourceRecord record = notification(
                "TABLE_SCAN_COMPLETED",
                Map.of(
                        "scanned_collection", "hr.emp",
                        "status", "SQL_EXCEPTION",
                        "connector_name", "opendrs.pipeline.7"));

        Optional<SnapshotNotifications.TableScanCompleted> parsed =
                SnapshotNotifications.parseTableScanCompleted(record);

        assertThat(parsed).isPresent();
        assertThat(parsed.get().succeeded()).isFalse();
        assertThat(parsed.get().totalRows()).isZero();
    }

    @Test
    void completedNotificationIsNotATableScan() {
        SourceRecord record = notification("COMPLETED", Map.of("connector_name", "opendrs.pipeline.7"));

        assertThat(SnapshotNotifications.parseTableScanCompleted(record)).isEmpty();
        assertThat(SnapshotNotifications.parseDataCollectionsResolved(record)).isEmpty();
        assertThat(SnapshotNotifications.isInitialSnapshotCompleted(record)).isTrue();
    }

    @Test
    void dataRecordIsNotANotification() {
        Schema schema = SchemaBuilder.struct().field("op", Schema.STRING_SCHEMA).build();
        SourceRecord data = new SourceRecord(
                null, null, "opendrs.pipeline.7.hr.emp", null, null, schema,
                new Struct(schema).put("op", "c"));

        assertThat(SnapshotNotifications.parseTableScanCompleted(data)).isEmpty();
        assertThat(SnapshotNotifications.parseDataCollectionsResolved(data)).isEmpty();
    }

    @Test
    void parsesDataCollectionsResolvedList() {
        SourceRecord record = notification(
                "DATA_COLLECTIONS_RESOLVED",
                Map.of(
                        "data_collections", "hr.dept,hr.emp",
                        "connector_name", "opendrs.pipeline.7"));

        Optional<List<String>> parsed = SnapshotNotifications.parseDataCollectionsResolved(record);

        assertThat(parsed).contains(List.of("hr.dept", "hr.emp"));
    }

    @Test
    void incrementalSnapshotNotificationsAreIgnored() {
        SourceRecord record = notification(
                "TABLE_SCAN_COMPLETED",
                Map.of("scanned_collection", "hr.emp", "status", "SUCCEEDED"));
        Struct value = (Struct) record.value();
        value.put("aggregate_type", "Incremental Snapshot");

        assertThat(SnapshotNotifications.parseTableScanCompleted(record)).isEmpty();
    }

    @Test
    void malformedRecordsParseEmpty() {
        Schema noAdditionalData = SchemaBuilder.struct()
                .field("aggregate_type", Schema.STRING_SCHEMA)
                .field("type", Schema.STRING_SCHEMA)
                .build();
        SourceRecord missingFields = new SourceRecord(
                null, null, "opendrs.pipeline.7.notification", noAdditionalData,
                new Struct(noAdditionalData).put("aggregate_type", "Initial Snapshot").put("type", "TABLE_SCAN_COMPLETED"));
        assertThat(SnapshotNotifications.parseTableScanCompleted(missingFields)).isEmpty();
        assertThat(SnapshotNotifications.parseDataCollectionsResolved(missingFields)).isEmpty();

        SourceRecord missingScannedCollection = notification(
                "TABLE_SCAN_COMPLETED", Map.of("status", "SUCCEEDED"));
        assertThat(SnapshotNotifications.parseTableScanCompleted(missingScannedCollection)).isEmpty();

        SourceRecord missingList = notification("DATA_COLLECTIONS_RESOLVED", Map.of());
        assertThat(SnapshotNotifications.parseDataCollectionsResolved(missingList)).isEmpty();
    }

    private static SourceRecord notification(String type, Map<String, String> additionalData) {
        Struct value = new Struct(NOTIFICATION_SCHEMA)
                .put("aggregate_type", "Initial Snapshot")
                .put("type", type)
                .put("additional_data", additionalData);
        return new SourceRecord(
                null, null, "opendrs.pipeline.7.notification", NOTIFICATION_SCHEMA, value);
    }
}
