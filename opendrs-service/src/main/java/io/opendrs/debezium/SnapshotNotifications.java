package io.opendrs.debezium;

import org.apache.kafka.connect.data.Struct;
import org.apache.kafka.connect.source.SourceRecord;

/**
 * Debezium sink-channel snapshot notifications. Used to persist {@code job_phase=INCREMENTAL}
 * without stopping the Engine.
 */
public final class SnapshotNotifications {

    public static final String INITIAL_SNAPSHOT = "Initial Snapshot";
    public static final String COMPLETED = "COMPLETED";

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
}
