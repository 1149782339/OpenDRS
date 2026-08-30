package io.opendrs.debezium;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class SchemaOnlySnapshotterTest {

    private final SchemaOnlySnapshotter snapshotter = new SchemaOnlySnapshotter();

    @Test
    void flagsMatchSchemaOnlyNoStream() {
        assertThat(snapshotter.name()).isEqualTo("opendrs_schema");
        assertThat(snapshotter.shouldSnapshotSchema(false, false)).isTrue();
        assertThat(snapshotter.shouldSnapshotSchema(true, false)).isTrue();
        assertThat(snapshotter.shouldSnapshotData(false, false)).isFalse();
        assertThat(snapshotter.shouldSnapshotData(true, true)).isFalse();
        assertThat(snapshotter.shouldStream()).isFalse();
        assertThat(snapshotter.shouldSnapshotOnSchemaError()).isFalse();
        assertThat(snapshotter.shouldSnapshotOnDataError()).isFalse();
        assertThat(snapshotter.shouldStreamEventsStartingFromSnapshot()).isFalse();
    }
}
