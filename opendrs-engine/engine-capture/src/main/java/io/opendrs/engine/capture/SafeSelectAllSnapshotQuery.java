package io.opendrs.engine.capture;

import io.debezium.snapshot.spi.SnapshotQuery;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Debezium's {@code SelectAllSnapshotQuery} minus the snapshot-connection-pool warmup probe, so a
 * pooled connection's first InnoDB read no longer freezes its read view before the snapshot
 * offset (P0) is taken.
 *
 * <p>With {@code snapshot.locking.mode=none} and {@code snapshot.max.threads > 1}, Debezium warms
 * every pooled snapshot connection with {@code SELECT * FROM <first captured table> LIMIT 1}
 * ({@code BinlogSnapshotChangeEventSource#getSnapshotConnectionFirstSelect}). The pooled
 * connection runs with auto-commit off at REPEATABLE READ, so that first plain SELECT fixes its
 * read view — while {@code RelationalSnapshotChangeEventSource} only records P0 (SHOW MASTER
 * STATUS, executed GTID set) <em>after</em> the pool is created. A transaction committed in
 * between is invisible to the snapshot readers yet already inside P0's GTID set, so binlog
 * streaming never replays it either: its rows stay permanently at the old value, with correct
 * row counts and no error anywhere (debezium/dbz#2942).
 *
 * <p>The warmup query is the one and only caller that passes a single {@code "*"} column
 * (verified against 3.7.0.Final), so returning empty for exactly that probe skips the warmup
 * while every real table read keeps the built-in SQL shape. The first read on each pooled
 * connection then happens after P0; transactions in the former gap now arrive twice (snapshot +
 * binlog), turning silent loss into duplication, which the sink's dup-key-to-upsert retry
 * absorbs. Loss is unacceptable, duplication is not.
 *
 * <p>The {@code "*"} convention is internal to Debezium — re-verify
 * {@code getSnapshotConnectionFirstSelect} when upgrading Debezium.
 */
public class SafeSelectAllSnapshotQuery implements SnapshotQuery {

    @Override
    public String name() {
        // ServiceLoader matches this against snapshot.query.mode.custom.name by exact equality.
        return getClass().getName();
    }

    @Override
    public void configure(Map<String, ?> properties) {
    }

    @Override
    public Optional<String> snapshotQuery(String tableId, List<String> columns) {
        if (columns.size() == 1 && "*".equals(columns.get(0))) {
            // The pool-warmup probe: skipping it keeps the read view unfrozen until P0 is taken.
            return Optional.empty();
        }
        // Byte-for-byte the built-in SelectAllSnapshotQuery — anything else changes real reads.
        return Optional.of(columns.stream()
                .collect(Collectors.joining(", ", "SELECT ", " FROM " + tableId)));
    }
}
