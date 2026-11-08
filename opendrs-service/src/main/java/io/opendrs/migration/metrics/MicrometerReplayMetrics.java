package io.opendrs.migration.metrics;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.opendrs.engine.replay.metrics.ReplayDirection;
import io.opendrs.engine.replay.metrics.ReplayMetrics;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Micrometer-backed {@link ReplayMetrics}: engine pipeline meters tagged with the task id and the
 * physical direction (a bidirectional task runs two pipelines under one task id). Prometheus
 * sees {@code opendrs_capture_events_total} and {@code opendrs_sink_apply_failures_total}
 * counters, the live replay lag as {@code opendrs_sink_replay_lag_seconds} (derived at scrape
 * time, decays to zero when the source goes idle), and two timers
 * ({@code opendrs_sink_tx_exec_time_seconds} / {@code opendrs_sink_tx_commit_time_seconds})
 * whose {@code _count} rate doubles as the replay TPS, all tagged {@code task_id} and
 * {@code direction}. Timers record without histogram buckets: each record is a couple of
 * atomic adds, so per-batch overhead stays in the nanosecond range.
 */
public final class MicrometerReplayMetrics implements ReplayMetrics {

    /** Per task+direction source-ts / apply-wall-clock pair; the gauge derives live lag at scrape time. */
    private final ConcurrentHashMap<String, LagState> lagStates = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Timer> execTimers = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Timer> commitTimers = new ConcurrentHashMap<>();

    /** Idle this long after the last commit means the source has nothing new: lag is zero. */
    private static final long IDLE_TIMEOUT_MS = 10_000;

    private final MeterRegistry registry;

    public MicrometerReplayMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    @Override
    public void captured(ReplayDirection direction, long taskId, int events) {
        registry.counter("opendrs.capture.events",
                        "task_id", String.valueOf(taskId), "direction", direction.tag())
                .increment(events);
    }

    @Override
    public void applyFailed(ReplayDirection direction, long taskId) {
        registry.counter("opendrs.sink.apply.failures",
                        "task_id", String.valueOf(taskId), "direction", direction.tag())
                .increment();
    }

    @Override
    public void txCommitted(
            ReplayDirection direction, long taskId, int txCount, double execMillis, double commitMillis) {
        if (txCount <= 0) {
            return;
        }
        String tag = key(direction, taskId);
        Timer exec = execTimers.computeIfAbsent(tag,
                k -> Timer.builder("opendrs.sink.tx.exec.time")
                        .tags("task_id", String.valueOf(taskId), "direction", direction.tag())
                        .register(registry));
        Timer commit = commitTimers.computeIfAbsent(tag,
                k -> Timer.builder("opendrs.sink.tx.commit.time")
                        .tags("task_id", String.valueOf(taskId), "direction", direction.tag())
                        .register(registry));
        long execNanos = (long) (execMillis * 1_000_000);
        long commitNanos = (long) (commitMillis * 1_000_000);
        for (int i = 0; i < txCount; i++) {
            exec.record(execNanos, TimeUnit.NANOSECONDS);
            commit.record(commitNanos, TimeUnit.NANOSECONDS);
        }
    }

    @Override
    public void appliedRows(ReplayDirection direction, long taskId, int rows) {
        if (rows <= 0) {
            return;
        }
        registry.counter("opendrs.sink.applied.rows",
                        "task_id", String.valueOf(taskId), "direction", direction.tag())
                .increment(rows);
    }

    @Override
    public void replayLag(ReplayDirection direction, long taskId, long eventSourceTsMs) {
        String tag = key(direction, taskId);
        lagStates.computeIfAbsent(tag, key -> {
            Gauge.builder("opendrs.sink.replay.lag.seconds", lagStates,
                            states -> states.get(key).lagSeconds())
                    .tags("task_id", String.valueOf(taskId), "direction", direction.tag())
                    .register(registry);
            return new LagState();
        }).onCommit(eventSourceTsMs);
    }

    private static String key(ReplayDirection direction, long taskId) {
        return taskId + "/" + direction.tag();
    }

    /**
     * Mutable per task+direction lag inputs. The gauge calls {@link #lagSeconds()} on every
     * Prometheus scrape, so the value is always computed against "now": it grows while the
     * target falls behind and decays to zero once the source has been idle past
     * {@link #IDLE_TIMEOUT_MS} (nothing left to replicate), instead of freezing at the last
     * committed measurement.
     */
    private static final class LagState {
        private volatile long eventTsMs;
        private volatile long applyTsMs;

        void onCommit(long eventSourceTsMs) {
            this.eventTsMs = eventSourceTsMs;
            this.applyTsMs = System.currentTimeMillis();
        }

        double lagSeconds() {
            long now = System.currentTimeMillis();
            if (applyTsMs == 0 || now - applyTsMs > IDLE_TIMEOUT_MS) {
                return 0.0;
            }
            return Math.max(0, (now - eventTsMs) / 1000.0);
        }
    }
}
