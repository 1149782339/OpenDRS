package io.opendrs.engine.capture;

import static org.assertj.core.api.Assertions.assertThat;

import io.debezium.snapshot.mode.InitialSnapshotter;
import org.junit.jupiter.api.Test;

class ResumableInitialSnapshotterTest {

    private final ResumableInitialSnapshotter snapshotter = new ResumableInitialSnapshotter();

    @Test
    void keepsInitialSnapshotDecisionsButStreamsFromStoredOffset() {
        assertThat(snapshotter).isInstanceOf(InitialSnapshotter.class);
        assertThat(snapshotter.name()).isEqualTo("opendrs_resumable");
        assertThat(snapshotter.name()).isNotEqualTo(new InitialSnapshotter().name());
        assertThat(snapshotter.shouldSnapshotData(false, false)).isTrue();
        assertThat(snapshotter.shouldSnapshotData(true, false)).isFalse();
        assertThat(snapshotter.shouldSnapshotData(true, true)).isTrue();
        assertThat(snapshotter.shouldStream()).isTrue();
        assertThat(snapshotter.shouldSnapshotOnSchemaError()).isFalse();
        assertThat(snapshotter.shouldSnapshotOnDataError()).isFalse();
        // The whole point of the resume mode: stream from the stored P0 position, not from the
        // snapshot marker, so pre-crash rows replayed between P0 and the resume are not skipped.
        assertThat(snapshotter.shouldStreamEventsStartingFromSnapshot()).isFalse();
    }
}
