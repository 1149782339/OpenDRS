package io.opendrs.engine.datacheck.incremental;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Map;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.Test;

class DataCheckLagTest {

    private static final TopicPartition TP = new TopicPartition("opendrs.pipeline.x", 0);
    private static final long NOW = 1_700_000_000_000L;

    @Test
    void zeroWhenCaughtUpAndNotWaiting() {
        assertEquals(0L, DataCheckLag.lagMs(NOW, false, NOW - 1_000, NOW - 1_000, 0L));
    }

    @Test
    void timeLagWhileWaitingUsesRecordTimestamps() {
        assertEquals(6_000L, DataCheckLag.lagMs(NOW, true, NOW - 2_000, NOW - 8_000, 40L));
    }

    @Test
    void waitingWithoutReplayTimestampUsesPendingRecordAge() {
        assertEquals(3_500L, DataCheckLag.lagMs(NOW, true, NOW - 3_500, null, 12L));
    }

    @Test
    void recordCountIsNeverUsedAsMilliseconds() {
        assertEquals(0L, DataCheckLag.lagMs(NOW, true, null, null, 99_000L));
    }

    @Test
    void backlogWhileWaitingIsRequiredPlusOneMinusCommitted() {
        long backlog = DataCheckLag.recordBacklog(
                Map.of(TP, 10L), Map.of(TP, 49L), Map.of(TP, 50L), null);
        assertEquals(40L, backlog);
    }

    @Test
    void idleBacklogIsLogEndMinusCommitted() {
        long backlog = DataCheckLag.recordBacklog(
                Map.of(TP, 10L), null, Map.of(TP, 10L), Map.of(TP, 35L));
        assertEquals(25L, backlog);
    }

    @Test
    void idleCaughtUpHasZeroBacklogAndZeroLag() {
        long backlog = DataCheckLag.recordBacklog(
                Map.of(TP, 35L), null, Map.of(TP, 35L), Map.of(TP, 35L));
        assertEquals(0L, backlog);
        assertEquals(0L, DataCheckLag.lagMs(NOW, false, NOW - 100, NOW - 100, backlog));
    }
}
