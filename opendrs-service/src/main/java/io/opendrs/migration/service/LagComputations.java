package io.opendrs.migration.service;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Pure lag formulas for capture, replay and end-to-end delay. Isolated from the clock and
 * Spring so the cases (idle, backlog, caught-up) can be unit-tested.
 *
 * <p>Same-process capture and replay share watermarks through {@link MigrationLagRegistry}.
 * If they run in separate processes, the replay side should refresh log-end via
 * {@code AdminClient.listOffsets} (or {@code consumer.endOffsets}) cached for 1–2s, and use
 * the consumed record's {@code timestamp()} as the last-applied CreateTime. Last-produced
 * CreateTime is then unavailable in the replay process; replay lag falls back to
 * “not caught up” with time lag only when both timestamps are known, and record backlog
 * still comes from log-end minus the last applied / committed offset. A shared store or
 * metric is needed for last-produced timestamps across processes.
 */
public final class LagComputations {

    /**
     * Fallback idle window used only when catch-up cannot be determined (no equal binlog
     * positions, no Kafka watermarks). Not applied once catch-up is known — that zeros
     * end-to-end lag immediately, with no wait.
     */
    public static final long IDLE_WINDOW_MS = 10_000L;

    /** A heartbeat older than this no longer proves the connector is alive. */
    public static final long ALIVE_WINDOW_MS = 120_000L;

    private LagComputations() {
    }

    /**
     * Immutable inputs for one lag computation.
     *
     * @param appliedEventTimeMs source.ts_ms of the last applied event
     * @param appliedAtMs host clock when that apply finished
     * @param heartbeatAtMs host clock of the last capture/replay heartbeat, 0 if none
     * @param capturedPosition last capture binlog position, or null
     * @param capturedAtMs when that capture position was published
     * @param appliedPosition last applied binlog position, or null
     * @param appliedPositionAtMs when that applied position was published
     * @param capturedEventTimeMs source.ts_ms of the last successfully captured event
     * @param capturedEventAtMs host clock when that capture was published
     * @param kafkaMode true once a produce/replay Kafka watermark has been seen
     * @param producedOffset inclusive last produced offset per partition
     * @param producedCreateTime last produced record CreateTime per partition
     * @param appliedOffset inclusive last applied (replayed) offset per partition
     * @param appliedCreateTime last applied record CreateTime per partition
     * @param logEndExclusive observed topic end (exclusive) per partition
     * @param sourcePosition live source binlog position, or null
     */
    public record Inputs(
            long appliedEventTimeMs,
            long appliedAtMs,
            long heartbeatAtMs,
            String capturedPosition,
            long capturedAtMs,
            String appliedPosition,
            long appliedPositionAtMs,
            long capturedEventTimeMs,
            long capturedEventAtMs,
            boolean kafkaMode,
            Map<Integer, Long> producedOffset,
            Map<Integer, Long> producedCreateTime,
            Map<Integer, Long> appliedOffset,
            Map<Integer, Long> appliedCreateTime,
            Map<Integer, Long> logEndExclusive,
            String sourcePosition) {
    }

    public record Result(long captureLagMs, long replayLagMs, long endToEndLagMs, long replayBacklog) {
        public static final Result ZERO = new Result(0L, 0L, 0L, 0L);
    }

    public static Result compute(Inputs in, long nowMs) {
        if (in == null) {
            return null;
        }
        boolean captureIdle = captureCaughtUpToSource(in, nowMs);
        long captureLag = captureLagMs(in, nowMs, captureIdle);
        boolean replayCaughtUp = replayCaughtUp(in);
        long replayLag = replayLagMs(in, replayCaughtUp);
        long backlog = replayBacklog(in);
        // KAFKA: caught up only if capture has reached the source AND replay has
        // consumed everything produced. DIRECT keeps the position-equal / idle fallback.
        boolean e2eCaughtUp = in.kafkaMode()
                ? (captureIdle && replayCaughtUp)
                : endToEndCaughtUp(in, nowMs, false);
        long e2e = endToEndLagMs(in, nowMs, e2eCaughtUp, captureLag, replayLag, replayCaughtUp);
        return new Result(captureLag, replayLag, e2e, backlog);
    }

    /**
     * {@code null} when there is no measurement yet or the connector looks dead with no
     * applied event (API shows a dash); otherwise the end-to-end lag.
     */
    public static Long endToEndOrUnknown(Inputs in, long nowMs) {
        if (in == null) {
            return null;
        }
        if (!in.kafkaMode()
                && in.appliedEventTimeMs() <= 0
                && in.heartbeatAtMs() > 0
                && nowMs - in.heartbeatAtMs() > ALIVE_WINDOW_MS) {
            return null;
        }
        Result result = compute(in, nowMs);
        return result == null ? null : result.endToEndLagMs();
    }

    static long captureLagMs(Inputs in, long nowMs, boolean captureIdle) {
        if (in.capturedEventTimeMs() <= 0 || captureIdle) {
            return 0L;
        }
        return Math.max(0L, nowMs - in.capturedEventTimeMs());
    }

    static long replayLagMs(Inputs in, boolean replayCaughtUp) {
        if (!in.kafkaMode() || replayCaughtUp) {
            return 0L;
        }
        long producedTs = maxValue(in.producedCreateTime());
        long appliedTs = maxValue(in.appliedCreateTime());
        if (producedTs <= 0 || appliedTs <= 0) {
            return 0L;
        }
        return Math.max(0L, producedTs - appliedTs);
    }

    static long endToEndLagMs(Inputs in, long nowMs, boolean caughtUp) {
        boolean replayCaughtUp = replayCaughtUp(in);
        long captureLag = captureLagMs(in, nowMs, captureCaughtUpToSource(in, nowMs));
        long replayLag = replayLagMs(in, replayCaughtUp);
        return endToEndLagMs(in, nowMs, caughtUp, captureLag, replayLag, replayCaughtUp);
    }

    /**
     * Zero only when capture is caught up to the source <em>and</em> replay is caught up.
     * Otherwise at least {@code max(captureLag, replayLag)}; if replay is still behind,
     * also at least the age of the last applied event.
     */
    static long endToEndLagMs(
            Inputs in,
            long nowMs,
            boolean caughtUp,
            long captureLag,
            long replayLag,
            boolean replayCaughtUp) {
        if (caughtUp) {
            return 0L;
        }
        long e2e = Math.max(captureLag, replayLag);
        if (!replayCaughtUp && in.appliedEventTimeMs() > 0) {
            e2e = Math.max(e2e, Math.max(0L, nowMs - in.appliedEventTimeMs()));
        }
        return e2e;
    }

    static boolean replayCaughtUp(Inputs in) {
        if (in.kafkaMode()) {
            return kafkaReplayCaughtUp(in);
        }
        return in.capturedPosition() != null
                && in.appliedPosition() != null
                && in.capturedPosition().equals(in.appliedPosition());
    }

    static boolean captureCaughtUpToSource(Inputs in, long nowMs) {
        if (in.sourcePosition() != null
                && in.capturedPosition() != null
                && in.sourcePosition().equals(in.capturedPosition())) {
            return true;
        }
        if (in.heartbeatAtMs() > 0
                && in.heartbeatAtMs() >= in.capturedEventAtMs()
                && nowMs - in.heartbeatAtMs() <= ALIVE_WINDOW_MS) {
            return true;
        }
        return false;
    }

    /**
     * KAFKA replay has consumed everything capture has produced (committed ≥ log-end).
     * Uses last applied offset vs last produced offset + 1 and/or observed log-end.
     */
    public static boolean kafkaReplayCaughtUp(Inputs in) {
        if (in == null || !in.kafkaMode()) {
            return false;
        }
        Set<Integer> partitions = new HashSet<>();
        addKeys(partitions, in.producedOffset());
        addKeys(partitions, in.appliedOffset());
        addKeys(partitions, in.logEndExclusive());
        if (partitions.isEmpty()) {
            return false;
        }
        boolean anyEnd = false;
        for (Integer partition : partitions) {
            long produced = get(in.producedOffset(), partition, -1L);
            long logEnd = get(in.logEndExclusive(), partition, 0L);
            long endExclusive = Math.max(produced < 0 ? 0L : produced + 1L, logEnd);
            if (endExclusive <= 0L) {
                continue;
            }
            anyEnd = true;
            long applied = get(in.appliedOffset(), partition, -1L);
            if (applied + 1L < endExclusive) {
                return false;
            }
        }
        return anyEnd;
    }

    static boolean endToEndCaughtUp(Inputs in, long nowMs, boolean kafkaCaughtUp) {
        boolean captureCaughtUp = captureCaughtUpToSource(in, nowMs);
        if (in.kafkaMode()) {
            return captureCaughtUp && kafkaCaughtUp;
        }
        if (in.capturedPosition() != null
                && in.appliedPosition() != null
                && in.capturedPosition().equals(in.appliedPosition())) {
            return captureCaughtUp;
        }
        // Fallback when positions/watermarks are missing (tests, startup): idle + alive.
        // Still requires capture to look idle when a source position is available.
        boolean alive = in.heartbeatAtMs() <= 0
                || nowMs - in.heartbeatAtMs() <= ALIVE_WINDOW_MS;
        if (alive && (in.appliedEventTimeMs() <= 0 || nowMs - in.appliedAtMs() > IDLE_WINDOW_MS)) {
            return in.sourcePosition() == null || captureCaughtUp;
        }
        return false;
    }

    public static long replayBacklog(Inputs in) {
        if (in == null || !in.kafkaMode()) {
            return 0L;
        }
        Set<Integer> partitions = new HashSet<>();
        addKeys(partitions, in.producedOffset());
        addKeys(partitions, in.appliedOffset());
        addKeys(partitions, in.logEndExclusive());
        long backlog = 0L;
        for (Integer partition : partitions) {
            long produced = get(in.producedOffset(), partition, -1L);
            long logEnd = get(in.logEndExclusive(), partition, 0L);
            long endExclusive = Math.max(produced < 0 ? 0L : produced + 1L, logEnd);
            long applied = get(in.appliedOffset(), partition, -1L);
            long committedNext = applied < 0 ? 0L : applied + 1L;
            backlog += Math.max(0L, endExclusive - committedNext);
        }
        return backlog;
    }

    private static long maxValue(Map<Integer, Long> values) {
        if (values == null || values.isEmpty()) {
            return 0L;
        }
        long max = 0L;
        for (Long value : values.values()) {
            if (value != null && value > max) {
                max = value;
            }
        }
        return max;
    }

    private static long get(Map<Integer, Long> map, Integer key, long defaultValue) {
        if (map == null) {
            return defaultValue;
        }
        Long value = map.get(key);
        return value == null ? defaultValue : value;
    }

    private static void addKeys(Set<Integer> into, Map<Integer, Long> map) {
        if (map != null) {
            into.addAll(map.keySet());
        }
    }
}
