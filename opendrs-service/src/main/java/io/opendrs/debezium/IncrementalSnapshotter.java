package io.opendrs.debezium;

import io.debezium.spi.snapshot.Snapshotter;
import java.util.Map;

/**
 * Streaming-only snapshotter (schema/data snapshot skipped). Kept on the Snapshotter SPI for unit
 * tests; the production capture path uses Debezium {@code snapshot.mode=initial} instead.
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
