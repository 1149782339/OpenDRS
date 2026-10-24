package io.opendrs.engine.capture;

import java.util.Locale;
import java.util.Set;
import org.apache.kafka.connect.data.Schema;
import org.apache.kafka.connect.data.Struct;
import org.apache.kafka.connect.source.SourceRecord;

/**
 * Reads the two envelope facts the lag gauge needs: when a change happened in the source, and
 * whether a record is a heartbeat.
 *
 * <p>Both live in the same place the applier already parses. A streaming record's
 * {@code source.ts_ms} is the binlog event header timestamp — the moment MySQL wrote the change —
 * and is whole seconds because that header is a 4-byte seconds value. A snapshot record carries the
 * same field, but there it means "when the snapshot read this row", which is always ≈ now and would
 * make a task that is still copying rows look perfectly caught up.
 *
 * <p>The snapshot marker is the same rule {@code SourceRecordConverter.isSnapshotPhase} applies:
 * Debezium writes {@code source.snapshot} only while it replays history and omits the field
 * entirely for live changes, so "no marker" is the streaming case. Two modules read the same
 * envelope here because the converter sits downstream in {@code engine-replay}, which this module
 * depends on, not the other way round.
 */
final class SourceEventTimes {

    /** Lower-cased {@code io.debezium.connector.SnapshotRecord} names that mean "snapshot phase". */
    private static final Set<String> SNAPSHOT_MARKERS =
            Set.of("true", "first", "first_in_data_collection", "last_in_data_collection", "last");

    /** Suffix of Debezium's heartbeat value schema name, when it carries one at all. */
    private static final String HEARTBEAT_EVENT = "Heartbeat";

    private SourceEventTimes() {
    }

    /**
     * The source timestamp of a live change, or {@code null} when the record is not one: snapshot
     * rows, heartbeats, transaction metadata, tombstones and the notification channel all fall out.
     */
    static Long streamingEventTimeMs(SourceRecord record) {
        if (record == null || !(record.value() instanceof Struct value)) {
            return null;
        }
        Schema schema = value.schema();
        if (schema == null || schema.field("source") == null) {
            return null;
        }
        Struct source = value.getStruct("source");
        if (source == null || source.schema() == null) {
            return null;
        }
        if (isSnapshot(source) || source.schema().field("ts_ms") == null) {
            return null;
        }
        Object eventTime = source.get("ts_ms");
        return eventTime instanceof Number number ? number.longValue() : null;
    }

    /**
     * A heartbeat has no value in the embedded engine; when a connector does attach one it is the
     * {@code Heartbeat} struct. The null-value branch also matches a delete tombstone, which is
     * harmless here: it only refreshes "the connector is alive", and this deployment writes no
     * tombstones ({@code tombstones.on.delete=false}).
     */
    static boolean isHeartbeat(SourceRecord record) {
        if (record == null) {
            return false;
        }
        Schema schema = record.valueSchema();
        if (schema != null && schema.name() != null && schema.name().endsWith(HEARTBEAT_EVENT)) {
            return true;
        }
        return record.value() == null && record.sourceOffset() != null;
    }

    private static boolean isSnapshot(Struct source) {
        if (source.schema().field("snapshot") == null) {
            return false;
        }
        Object marker = source.get("snapshot");
        return marker != null && SNAPSHOT_MARKERS.contains(marker.toString().toLowerCase(Locale.ROOT));
    }
}
