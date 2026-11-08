package io.opendrs.migration.service;

import static org.assertj.core.api.Assertions.assertThat;

import io.opendrs.common.progress.MigrationLagListener;
import io.opendrs.migration.service.MigrationLagRegistry.Sample;
import org.junit.jupiter.api.Test;

/**
 * The rule, on a frozen clock. The cases are the ones that decide whether this gauge tells the truth:
 * a quiet source must not look stuck, a stuck pipeline must not look healthy, and a skewed source
 * clock must not produce a negative number.
 */
class MigrationLagRegistryTest {

    private static final long NOW = 1_700_000_000_000L;
    private static final long IDLE = MigrationLagRegistry.IDLE_WINDOW_MS;
    private static final long ALIVE = MigrationLagRegistry.ALIVE_WINDOW_MS;

    @Test
    void noSampleMeansUnknown() {
        assertThat(MigrationLagRegistry.lagMs(null, NOW)).isNull();
    }

    @Test
    void aQuietSourceWithAHeartbeatMeansCaughtUp() {
        // Nothing applied for a minute, but the connector is still beating: the source is simply
        // idle and the target is caught up. Without this the number would climb to a minute.
        Sample sample = new Sample(NOW - 60_000, NOW - 60_000, NOW - 40_000);

        assertThat(MigrationLagRegistry.lagMs(sample, NOW)).isZero();
    }

    @Test
    void aFreshChangeIsReportedEvenThoughTheSourceIsAlive() {
        // Heartbeats must not swallow real lag: data is flowing, so the raw distance wins.
        Sample sample = new Sample(NOW - 2_500, NOW - 100, NOW - 1_000);

        assertThat(MigrationLagRegistry.lagMs(sample, NOW)).isEqualTo(2_500);
    }

    @Test
    void changesNewerThanTheHeartbeatAreMeasured() {
        Sample sample = new Sample(NOW - 700, NOW, NOW - 5_000);

        assertThat(MigrationLagRegistry.lagMs(sample, NOW)).isEqualTo(700);
    }

    @Test
    void aStalledPipelineKeepsGrowing() {
        // No heartbeat for longer than the alive window: the connector is not reading any more, so
        // the honest answer is the raw distance from the last change, and it must keep climbing
        // while the operator watches.
        Sample sample = new Sample(NOW - 180_000, NOW - 180_000, NOW - 180_000);

        assertThat(MigrationLagRegistry.lagMs(sample, NOW)).isEqualTo(180_000);
        assertThat(MigrationLagRegistry.lagMs(sample, NOW + 10_000)).isEqualTo(190_000);
    }

    @Test
    void aSourceClockAheadOfOursIsClampedAtZero() {
        Sample sample = new Sample(NOW + 3_000, NOW, NOW - 1_000);

        assertThat(MigrationLagRegistry.lagMs(sample, NOW)).isZero();
    }

    @Test
    void noChangeYetIsZeroBeforeTheFirstHeartbeat() {
        // The run just started: the engine always configures heartbeats, so having none means the
        // first one has not arrived yet, not that the connector is dead.
        assertThat(MigrationLagRegistry.lagMs(new Sample(0, 0, 0), NOW)).isZero();
    }

    @Test
    void aRestartedRunDoesNotRampWhileWaitingForItsFirstHeartbeat() {
        // Applied 40s ago, no heartbeat yet (a fresh JVM): caught up, not 40 seconds behind.
        Sample coldStart = new Sample(NOW - 40_000, NOW - 40_000, 0);

        assertThat(MigrationLagRegistry.lagMs(coldStart, NOW)).isZero();
    }

    @Test
    void noChangeYetIsZeroWhileHeartbeatsArrive() {
        // Streaming has started, the source is quiet, the connector is at the binlog head.
        assertThat(MigrationLagRegistry.lagMs(new Sample(0, 0, NOW - 2_000), NOW)).isZero();
    }

    @Test
    void heartbeatOnlyTaskGoesUnknownOnceHeartbeatsStop() {
        Sample recent = new Sample(0, 0, NOW - IDLE - 1);
        Sample stale = new Sample(0, 0, NOW - ALIVE - 1);

        assertThat(MigrationLagRegistry.lagMs(recent, NOW)).isZero();
        assertThat(MigrationLagRegistry.lagMs(stale, NOW)).isNull();
    }

    @Test
    void listenerBindsTheTaskAndSurvivesClear() {
        MigrationLagRegistry registry = new MigrationLagRegistry();
        MigrationLagListener listener = registry.listenerFor(7L);
        long now = System.currentTimeMillis();

        listener.onApplied(now - 1_200, now);
        listener.onHeartbeat(now);

        assertThat(registry.lagMs(7L))
                .hasValueSatisfying(lag -> assertThat(lag).isBetween(1_000L, 3_000L));
        assertThat(registry.lagMs(8L)).isEmpty();

        registry.clear(7L);

        assertThat(registry.lagMs(7L)).isEmpty();
    }

    @Test
    void aLateHeartbeatDoesNotRewindTheEventTime() {
        MigrationLagRegistry registry = new MigrationLagRegistry();
        MigrationLagListener listener = registry.listenerFor(1L);
        long now = System.currentTimeMillis();

        listener.onApplied(now - 500, now);
        listener.onHeartbeat(now);
        // A second batch whose event time is older (source clock stepped back) must not move the
        // gauge backwards to nine seconds; the newer event time survives.
        listener.onApplied(now - 9_000, now + 10);

        assertThat(registry.lagMs(1L))
                .hasValueSatisfying(lag -> assertThat(lag).isLessThan(2_000L));
    }
}
