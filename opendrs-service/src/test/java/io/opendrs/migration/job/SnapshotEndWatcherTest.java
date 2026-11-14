package io.opendrs.migration.job;

import static org.assertj.core.api.Assertions.assertThat;

import io.opendrs.common.progress.MigrationLagListener;
import io.opendrs.engine.jdbc.binlog.BinlogPosition;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class SnapshotEndWatcherTest {

    private static final String UUID_A = "3e11fa47-71ca-11e1-9e33-c80aa9429562";

    @Test
    void firesOnceAfterArmingWhenAppliedGtidsContainTheSnapshotEnd() {
        AtomicInteger fired = new AtomicInteger();
        List<Map<String, ?>> forwarded = new ArrayList<>();
        MigrationLagListener delegate = new MigrationLagListener() {
            @Override
            public void onApplied(long sourceEventTimeMs, long appliedAtMs) {
            }

            @Override
            public void onAppliedOffset(Map<String, ?> sourceOffset, long atMs) {
                forwarded.add(sourceOffset);
            }
        };
        SnapshotEndWatcher watcher = new SnapshotEndWatcher(delegate, fired::incrementAndGet);
        Map<String, ?> past = Map.of("file", "mysql-bin.000009", "pos", 10L, "gtids", UUID_A + ":1-200");

        watcher.emitAppliedOffset(past, 0L);
        assertThat(fired).hasValue(0);

        watcher.arm(new BinlogPosition("mysql-bin.000009", 5000L, UUID_A + ":1-100"));
        watcher.emitAppliedOffset(Map.of("file", "mysql-bin.000009", "pos", 9000L, "gtids", UUID_A + ":1-99"), 0L);
        assertThat(fired).hasValue(0);
        // GTID containment wins over the file position (pos is the start of the current transaction).
        watcher.emitAppliedOffset(past, 0L);
        watcher.emitAppliedOffset(past, 0L);
        assertThat(fired).hasValue(1);
        assertThat(watcher.fired()).isTrue();
        assertThat(forwarded).hasSize(4);
    }

    @Test
    void snapshotOffsetsNeverCount() {
        AtomicInteger fired = new AtomicInteger();
        SnapshotEndWatcher watcher = new SnapshotEndWatcher(null, fired::incrementAndGet);
        watcher.arm(new BinlogPosition("mysql-bin.000001", 100L, null));

        watcher.emitAppliedOffset(Map.of("file", "mysql-bin.000002", "pos", 4L, "snapshot", true), 0L);
        assertThat(fired).hasValue(0);
        watcher.emitAppliedOffset(Map.of("file", "mysql-bin.000002", "pos", 4L), 0L);
        assertThat(fired).hasValue(1);
    }
}
