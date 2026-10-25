package io.opendrs.engine.capture;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.apache.kafka.connect.data.Struct;
import org.apache.kafka.connect.source.SourceRecord;

/**
 * Debezium sink-channel snapshot notifications. Used to persist {@code job_phase=INCREMENTAL}
 * without stopping the Engine, and to drive per-table full-load checkpoints.
 *
 * <p>Sink-channel records carry the notification as a value {@link Struct} with
 * {@code aggregate_type}/{@code type} strings and an {@code additional_data} string map —
 * see {@code io.debezium.pipeline.notification.channels.SinkNotificationChannel} and
 * {@code InitialSnapshotNotificationService} in Debezium. The service emits
 * {@code DATA_COLLECTIONS_RESOLVED} once when the snapshot's table list is known and
 * {@code TABLE_SCAN_COMPLETED} after each table's rows, always <em>after</em> that table's
 * data records in the record stream.
 */
public final class SnapshotNotifications {

    public static final String INITIAL_SNAPSHOT = "Initial Snapshot";
    public static final String COMPLETED = "COMPLETED";

    private static final String TABLE_SCAN_COMPLETED = "TABLE_SCAN_COMPLETED";
    private static final String DATA_COLLECTIONS_RESOLVED = "DATA_COLLECTIONS_RESOLVED";

    private static final String ADDITIONAL_DATA = "additional_data";
    private static final String SCANNED_COLLECTION = "scanned_collection";
    private static final String TOTAL_ROWS_SCANNED = "total_rows_scanned";
    private static final String STATUS = "status";
    private static final String DATA_COLLECTIONS = "data_collections";
    private static final String SCAN_SUCCEEDED = "SUCCEEDED";

    private SnapshotNotifications() {
    }

    public static boolean isInitialSnapshotCompleted(SourceRecord record) {
        Object value = record.value();
        if (!(value instanceof Struct struct)) {
            return false;
        }
        if (struct.schema().field("aggregate_type") == null || struct.schema().field("type") == null) {
            return false;
        }
        return INITIAL_SNAPSHOT.equals(struct.getString("aggregate_type"))
                && COMPLETED.equals(struct.getString("type"));
    }

    /**
     * One table's scan outcome. {@code succeeded} mirrors the {@code status} entry Debezium
     * reports ({@code SUCCEEDED} vs e.g. {@code SQL_EXCEPTION}): a failed scan still yields a
     * record here (so the caller can log it) but must never be treated as "table done".
     */
    public record TableScanCompleted(String table, long totalRows, boolean succeeded) {
    }

    /**
     * Parses a {@code TABLE_SCAN_COMPLETED} notification into the scanned table and row count.
     * Returns empty for any other record shape (data records, {@code COMPLETED}, incremental
     * notifications). A non-{@code SUCCEEDED} status parses to {@code succeeded=false} rather
     * than empty: the table failed mid-snapshot and the caller should log it, not mark it done.
     */
    public static Optional<TableScanCompleted> parseTableScanCompleted(SourceRecord record) {
        Optional<Map<String, Object>> additional = initialSnapshotAdditionalData(record, TABLE_SCAN_COMPLETED);
        if (additional.isEmpty()) {
            return Optional.empty();
        }
        Map<String, Object> data = additional.get();
        Object table = data.get(SCANNED_COLLECTION);
        if (!(table instanceof String tableName) || tableName.isBlank()) {
            return Optional.empty();
        }
        long rows = 0;
        Object scanned = data.get(TOTAL_ROWS_SCANNED);
        if (scanned instanceof Number number) {
            rows = number.longValue();
        }
        else if (scanned != null) {
            try {
                rows = Long.parseLong(scanned.toString());
            }
            catch (NumberFormatException ignored) {
                rows = 0;
            }
        }
        return Optional.of(new TableScanCompleted(
                tableName, rows, SCAN_SUCCEEDED.equals(String.valueOf(data.get(STATUS)))));
    }

    /**
     * Parses a {@code DATA_COLLECTIONS_RESOLVED} notification into the {@code db.table} list
     * Debezium resolved for the snapshot (comma-joined in {@code additional_data}). On a resume
     * run this is the remaining-tables list, not the original full selection. Empty for any
     * other record shape or when no list is present.
     */
    public static Optional<List<String>> parseDataCollectionsResolved(SourceRecord record) {
        Optional<Map<String, Object>> additional = initialSnapshotAdditionalData(record, DATA_COLLECTIONS_RESOLVED);
        if (additional.isEmpty()) {
            return Optional.empty();
        }
        Object collections = additional.get().get(DATA_COLLECTIONS);
        if (!(collections instanceof String joined) || joined.isBlank()) {
            return Optional.empty();
        }
        List<String> tables = new ArrayList<>();
        for (String table : joined.split(",")) {
            String trimmed = table.trim();
            if (!trimmed.isEmpty()) {
                tables.add(trimmed);
            }
        }
        return tables.isEmpty() ? Optional.empty() : Optional.of(List.copyOf(tables));
    }

    /**
     * The {@code additional_data} map of an {@code aggregate_type=Initial Snapshot} record of
     * the given type, or empty when the record is not such a notification. The sink channel
     * hands the map through as-is ({@code Map<String, String>}); anything else (Struct, missing
     * fields) means a foreign record shape and is ignored.
     */
    private static Optional<Map<String, Object>> initialSnapshotAdditionalData(
            SourceRecord record, String type) {
        Object value = record.value();
        if (!(value instanceof Struct struct)) {
            return Optional.empty();
        }
        if (struct.schema().field("aggregate_type") == null
                || struct.schema().field("type") == null
                || struct.schema().field(ADDITIONAL_DATA) == null) {
            return Optional.empty();
        }
        if (!INITIAL_SNAPSHOT.equals(struct.getString("aggregate_type"))
                || !type.equals(struct.getString("type"))) {
            return Optional.empty();
        }
        Object data = struct.get(ADDITIONAL_DATA);
        if (!(data instanceof Map<?, ?> map)) {
            return Optional.empty();
        }
        return Optional.of(castMap(map));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Map<?, ?> map) {
        return (Map<String, Object>) map;
    }
}
