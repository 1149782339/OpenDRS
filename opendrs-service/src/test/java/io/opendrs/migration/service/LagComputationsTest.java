package io.opendrs.migration.service;

import static org.assertj.core.api.Assertions.assertThat;

import io.opendrs.migration.service.LagComputations.Inputs;
import io.opendrs.migration.service.LagComputations.Result;
import java.util.Map;
import org.junit.jupiter.api.Test;

class LagComputationsTest {

    private static final long NOW = 1_700_000_000_000L;

    @Test
    void kafkaBacklogReportsAllThreeLagsAndRecordCount() {
        Inputs in = kafka(
                NOW - 8_000, NOW - 100, 0L,
                "bin:100", NOW - 200, "bin:50", NOW - 100,
                NOW - 4_000, NOW - 200,
                Map.of(0, 40L), Map.of(0, NOW - 1_000),
                Map.of(0, 10L), Map.of(0, NOW - 5_000),
                Map.of(),
                "bin:120");

        Result result = LagComputations.compute(in, NOW);

        assertThat(result.endToEndLagMs()).isEqualTo(8_000L);
        assertThat(result.replayLagMs()).isEqualTo(4_000L);
        assertThat(result.captureLagMs()).isEqualTo(4_000L);
        assertThat(result.replayBacklog()).isEqualTo(30L);
        assertThat(LagComputations.kafkaReplayCaughtUp(in)).isFalse();
    }

    @Test
    void kafkaCaughtUpZerosE2eAndReplayImmediatelyWithoutIdleWait() {
        // Last event is 20s old and was just applied (the old idle-window bug).
        Inputs in = kafka(
                NOW - 20_000, NOW, 0L,
                "bin:200", NOW, "bin:200", NOW,
                NOW - 20_000, NOW - 50,
                Map.of(0, 99L), Map.of(0, NOW - 20),
                Map.of(0, 99L), Map.of(0, NOW - 20),
                Map.of(0, 100L),
                "bin:200");

        Result result = LagComputations.compute(in, NOW);

        assertThat(LagComputations.kafkaReplayCaughtUp(in)).isTrue();
        assertThat(result.endToEndLagMs()).isZero();
        assertThat(result.replayLagMs()).isZero();
        assertThat(result.replayBacklog()).isZero();
        // source == captured → capture also idle
        assertThat(result.captureLagMs()).isZero();
    }

    @Test
    void directPositionsEqualZerosE2eImmediately() {
        Inputs in = direct(
                NOW - 60_000, NOW, 0L,
                "bin:9", NOW, "bin:9", NOW,
                NOW - 60_000, NOW);
        Result result = LagComputations.compute(in, NOW);
        assertThat(result.endToEndLagMs()).isZero();
        assertThat(result.replayLagMs()).isZero();
        assertThat(result.replayBacklog()).isZero();
    }

    @Test
    void directPositionsDifferKeepE2eOnLastAppliedEvent() {
        Inputs in = direct(
                NOW - 3_000, NOW - 10, 0L,
                "bin:20", NOW - 10, "bin:10", NOW - 100,
                NOW - 1_000, NOW - 10);
        Result result = LagComputations.compute(in, NOW);
        assertThat(result.endToEndLagMs()).isEqualTo(3_000L);
        assertThat(result.captureLagMs()).isEqualTo(1_000L);
        assertThat(result.replayLagMs()).isZero();
    }

    @Test
    void captureLagIsZeroOnHeartbeatAfterLastCapture() {
        Inputs in = kafka(
                NOW - 15_000, NOW - 15_000, NOW - 100,
                "bin:5", NOW - 15_000, "bin:5", NOW - 15_000,
                NOW - 15_000, NOW - 15_000,
                Map.of(0, 5L), Map.of(0, NOW - 15_000),
                Map.of(0, 5L), Map.of(0, NOW - 15_000),
                Map.of(0, 6L),
                "bin:8");
        Result result = LagComputations.compute(in, NOW);
        assertThat(result.captureLagMs()).isZero();
        assertThat(result.endToEndLagMs()).isZero();
    }

    @Test
    void captureLagGrowsWhenConnectorIsNotIdle() {
        Inputs in = kafka(
                NOW - 9_000, NOW - 2_000, 0L,
                "bin:50", NOW - 400, "bin:10", NOW - 2_000,
                NOW - 7_000, NOW - 400,
                Map.of(0, 80L), Map.of(0, NOW - 200),
                Map.of(0, 20L), Map.of(0, NOW - 3_000),
                Map.of(),
                "bin:90");
        Result result = LagComputations.compute(in, NOW);
        assertThat(result.captureLagMs()).isEqualTo(7_000L);
        assertThat(result.replayLagMs()).isEqualTo(2_800L);
        assertThat(result.endToEndLagMs()).isEqualTo(9_000L);
        assertThat(result.replayBacklog()).isEqualTo(60L);
    }

    @Test
    void stalledKafkaWithoutCatchUpKeepsGrowingE2e() {
        Inputs in = kafka(
                NOW - 180_000, NOW - 180_000, NOW - 180_000,
                "bin:80", NOW - 180_000, "bin:10", NOW - 180_000,
                NOW - 180_000, NOW - 180_000,
                Map.of(0, 80L), Map.of(0, NOW - 180_000),
                Map.of(0, 10L), Map.of(0, NOW - 180_000),
                Map.of(),
                "bin:80");
        assertThat(LagComputations.compute(in, NOW).endToEndLagMs()).isEqualTo(180_000L);
        assertThat(LagComputations.compute(in, NOW + 10_000).endToEndLagMs()).isEqualTo(190_000L);
    }

    @Test
    void fallbackIdleStillZerosWhenCatchUpCannotBeDetermined() {
        Inputs in = new Inputs(
                NOW - 60_000, NOW - 60_000, NOW - 40_000,
                null, 0L, null, 0L,
                0L, 0L, false,
                Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), null);
        assertThat(LagComputations.compute(in, NOW).endToEndLagMs()).isZero();
    }

    private static Inputs kafka(
            long appliedEvent,
            long appliedAt,
            long heartbeat,
            String capturedPos,
            long capturedAt,
            String appliedPos,
            long appliedPosAt,
            long capturedEvent,
            long capturedEventAt,
            Map<Integer, Long> producedOffset,
            Map<Integer, Long> producedCreateTime,
            Map<Integer, Long> appliedOffset,
            Map<Integer, Long> appliedCreateTime,
            Map<Integer, Long> logEnd,
            String sourcePosition) {
        return new Inputs(
                appliedEvent, appliedAt, heartbeat,
                capturedPos, capturedAt, appliedPos, appliedPosAt,
                capturedEvent, capturedEventAt, true,
                producedOffset, producedCreateTime, appliedOffset, appliedCreateTime, logEnd,
                sourcePosition);
    }

    private static Inputs direct(
            long appliedEvent,
            long appliedAt,
            long heartbeat,
            String capturedPos,
            long capturedAt,
            String appliedPos,
            long appliedPosAt,
            long capturedEvent,
            long capturedEventAt) {
        return new Inputs(
                appliedEvent, appliedAt, heartbeat,
                capturedPos, capturedAt, appliedPos, appliedPosAt,
                capturedEvent, capturedEventAt, false,
                Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), null);
    }
}
