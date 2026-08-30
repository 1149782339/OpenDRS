package io.opendrs.debezium;

import io.debezium.spi.snapshot.Snapshotter;
import java.util.Map;

/**
 * Streaming-only snapshotter for the second Engine round. Schema/data snapshot is skipped so an
 * existing offset from {@link SchemaOnlySnapshotter} is reused.
 */
public class IncrementalSnapshotter implements Snapshotter {

    public static final String NAME = "opendrs_incremental";

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public void configure(Map<String, ?> properties) {
    }

    @Override
    public boolean shouldSnapshotSchema(boolean offsetExists, boolean snapshotInProgress) {
        return false;
    }

    @Override
    public boolean shouldSnapshotData(boolean offsetExists, boolean snapshotInProgress) {
        return false;
    }

    @Override
    public boolean shouldStream() {
        return true;
    }

    @Override
    public boolean shouldSnapshotOnSchemaError() {
        return false;
    }

    @Override
    public boolean shouldSnapshotOnDataError() {
        return false;
    }

    @Override
    public boolean shouldStreamEventsStartingFromSnapshot() {
        return false;
    }
}
