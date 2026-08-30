package io.opendrs.debezium;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class IncrementalSnapshotterTest {

    private final IncrementalSnapshotter snapshotter = new IncrementalSnapshotter();

    @Test
    void flagsSkipSnapshotAndStream() {
        assertThat(snapshotter.name()).isEqualTo("opendrs_incremental");
        assertThat(snapshotter.shouldSnapshotSchema(false, false)).isFalse();
        assertThat(snapshotter.shouldSnapshotSchema(true, false)).isFalse();
        assertThat(snapshotter.shouldSnapshotData(false, false)).isFalse();
        assertThat(snapshotter.shouldStream()).isTrue();
        assertThat(snapshotter.shouldStreamEventsStartingFromSnapshot()).isFalse();
    }
}
