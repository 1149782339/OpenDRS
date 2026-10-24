package io.opendrs.engine.capture;

import io.debezium.engine.DebeziumEngine;
import io.debezium.engine.RecordChangeEvent;
import io.opendrs.common.progress.MigrationLagListener;
import java.util.List;
import org.apache.kafka.connect.source.SourceRecord;

/**
 * Measures the lag gauge: wraps the consumer that writes to the target and reports, once per batch,
 * the source timestamp of the newest change that is now committed.
 *
 * <p>It sits <em>outside</em> {@link ReplayApplyChangeConsumer} on purpose. That consumer returns only
 * after {@code JdbcApplier} committed the batch, so "the delegate returned" is exactly "the target
 * has these rows", which is the definition the lag is supposed to express. Measuring before the
 * commit would count rows that might still be rolled back.
 *
 * <p>Publishing timestamps instead of a difference is what keeps a stalled pipeline visible: the
 * reader subtracts {@code now} itself, so the number grows when nothing arrives. The batch scan is
 * two field reads per record and allocates nothing; it runs after the commit, so it never delays
 * binlog consumption.
 */
public final class LagTrackingConsumer
        implements DebeziumEngine.ChangeConsumer<RecordChangeEvent<SourceRecord>> {

    private final DebeziumEngine.ChangeConsumer<RecordChangeEvent<SourceRecord>> delegate;
    private final MigrationLagListener lagListener;

    public LagTrackingConsumer(
            DebeziumEngine.ChangeConsumer<RecordChangeEvent<SourceRecord>> delegate,
            MigrationLagListener lagListener) {
        this.delegate = delegate;
        this.lagListener = lagListener == null ? MigrationLagListener.NONE : lagListener;
    }

    @Override
    public void handleBatch(
            List<RecordChangeEvent<SourceRecord>> records,
            DebeziumEngine.RecordCommitter<RecordChangeEvent<SourceRecord>> committer)
            throws InterruptedException {
        // Throws on a rejected batch, and then nothing is published: the target does not have it.
        delegate.handleBatch(records, committer);

        long newest = Long.MIN_VALUE;
        boolean heartbeat = false;
        for (RecordChangeEvent<SourceRecord> event : records) {
            SourceRecord record = event.record();
            Long eventTime = SourceEventTimes.streamingEventTimeMs(record);
            if (eventTime != null) {
                // Max, not last: a source clock that steps backwards must not make the gauge retreat.
                newest = Math.max(newest, eventTime);
            } else if (SourceEventTimes.isHeartbeat(record)) {
                heartbeat = true;
            }
        }
        long now = System.currentTimeMillis();
        if (newest != Long.MIN_VALUE) {
            lagListener.emitApplied(newest, now);
        } else if (heartbeat) {
            lagListener.emitHeartbeat(now);
        }
    }
}
