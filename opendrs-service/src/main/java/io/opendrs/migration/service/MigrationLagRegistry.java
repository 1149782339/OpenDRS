package io.opendrs.migration.service;

import io.opendrs.common.progress.MigrationLagListener;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * Live lag gauges for running pipelines. Capture and replay (when they share this process)
 * push watermarks here; HTTP and Prometheus readers compute lag at read time.
 *
 * <p>Nothing is persisted: lag is a gauge. After a restart the dispatcher re-runs the task
 * and the entry is rebuilt within seconds.
 *
 * <p><strong>Split-process capture / replay.</strong> This registry is in-memory and not
 * shared across JVMs. If capture and replay run in separate processes:
 * <ul>
 *   <li>Replay should cache {@code AdminClient.listOffsets} / {@code consumer.endOffsets}
 *       for 1–2s and call {@link MigrationLagListener#onLogEnd} so replay lag and backlog
 *       can be computed without a per-poll timestamp seek.</li>
 *   <li>Last-applied CreateTime comes from the consumed record's {@code timestamp()}
 *       ({@link MigrationLagListener#onReplayed}).</li>
 *   <li>Last-produced CreateTime lives on the producer side; without a shared store or
 *       metric, the replay process can still report record backlog and
 *       caught-up-when-committed ≥ log-end, but not
 *       {@code ts(last produced) − ts(last applied)}.</li>
 *   <li>End-to-end lag on the replay side still uses source.ts_ms of the last applied
 *       event, zeroed immediately when committed ≥ log-end.</li>
 * </ul>
 */
@Component
public class MigrationLagRegistry {

    /** @deprecated use {@link LagComputations#IDLE_WINDOW_MS} */
    static final long IDLE_WINDOW_MS = LagComputations.IDLE_WINDOW_MS;

    /** @deprecated use {@link LagComputations#ALIVE_WINDOW_MS} */
    static final long ALIVE_WINDOW_MS = LagComputations.ALIVE_WINDOW_MS;

    private final Map<Long, Sample> samples = new ConcurrentHashMap<>();
    private volatile MeterBinder meterBinder = MeterBinder.NONE;
    private volatile java.util.function.LongFunction<String> sourcePositionLookup = id -> null;

    /** Optional hook so {@code MigrationLagMetrics} can register per-pipeline gauges. */
    public interface MeterBinder {
        MeterBinder NONE = (pipelineId, taskId) -> { };

        void bind(long pipelineId, long taskId);
    }

    public void setMeterBinder(MeterBinder meterBinder) {
        this.meterBinder = meterBinder == null ? MeterBinder.NONE : meterBinder;
    }

    /**
     * Optional live source-binlog lookup so Prometheus gauges use the same e2e formula as the API
     * (capture lag requires {@code sourcePosition}).
     */
    public void setSourcePositionLookup(java.util.function.LongFunction<String> sourcePositionLookup) {
        this.sourcePositionLookup = sourcePositionLookup == null ? id -> null : sourcePositionLookup;
    }

    private String lookupSourcePosition(Long pipelineId) {
        if (pipelineId == null) {
            return null;
        }
        try {
            return sourcePositionLookup.apply(pipelineId);
        } catch (RuntimeException ex) {
            return null;
        }
    }

    /** The observer handed to {@code DataMigrationParam}; binds the pipeline id so the engine never sees one. */
    public MigrationLagListener listenerFor(long pipelineId) {
        return listenerFor(pipelineId, 0L);
    }

    public MigrationLagListener listenerFor(long pipelineId, long taskId) {
        Sample sample = samples.computeIfAbsent(pipelineId, id -> new Sample());
        if (taskId > 0L) {
            sample.taskId = taskId;
        }
        meterBinder.bind(pipelineId, sample.taskId);
        return new Listener(sample);
    }

    /** Milliseconds the target is behind (end-to-end), or empty when this pipeline has nothing to measure yet. */
    public Optional<Long> lagMs(Long pipelineId) {
        return lagMs(pipelineId, System.currentTimeMillis(), lookupSourcePosition(pipelineId));
    }

    public Optional<Long> lagMs(Long pipelineId, long nowMs, String sourcePosition) {
        if (pipelineId == null) {
            return Optional.empty();
        }
        Sample sample = samples.get(pipelineId);
        if (sample == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(LagComputations.endToEndOrUnknown(sample.inputs(sourcePosition), nowMs));
    }

    /** All four lag numbers, or empty when this pipeline has no sample yet. */
    public Optional<Lags> lags(Long pipelineId) {
        return lags(pipelineId, System.currentTimeMillis(), lookupSourcePosition(pipelineId));
    }

    public Optional<Lags> lags(Long pipelineId, long nowMs, String sourcePosition) {
        if (pipelineId == null) {
            return Optional.empty();
        }
        Sample sample = samples.get(pipelineId);
        if (sample == null) {
            return Optional.empty();
        }
        LagComputations.Result result = LagComputations.compute(sample.inputs(sourcePosition), nowMs);
        if (result == null) {
            return Optional.empty();
        }
        return Optional.of(new Lags(
                result.captureLagMs(), result.replayLagMs(), result.endToEndLagMs(), result.replayBacklog()));
    }

    /**
     * Capture/apply positions for the task detail page, or empty when no batch was seen
     * since the run started.
     */
    public Optional<Positions> positions(Long pipelineId) {
        if (pipelineId == null) {
            return Optional.empty();
        }
        Sample sample = samples.get(pipelineId);
        if (sample == null) {
            return Optional.empty();
        }
        return Optional.of(new Positions(
                sample.capturedPosition,
                sample.capturedAtMs,
                sample.appliedPosition,
                sample.appliedPositionAtMs));
    }

    /** Last applied Kafka record CreateTime across partitions, or empty. */
    public Optional<Long> lastAppliedCreateTimeMs(Long pipelineId) {
        if (pipelineId == null) {
            return Optional.empty();
        }
        Sample sample = samples.get(pipelineId);
        if (sample == null || sample.appliedCreateTime.isEmpty()) {
            return Optional.empty();
        }
        long max = 0L;
        for (Long value : sample.appliedCreateTime.values()) {
            if (value != null && value > max) {
                max = value;
            }
        }
        return max > 0L ? Optional.of(max) : Optional.empty();
    }

    /**
     * Replay committed next-to-read offsets (last applied offset + 1) keyed by partition,
     * for same-process datacheck lag against this registry.
     */
    public Map<Integer, Long> replayCommittedNext(Long pipelineId) {
        if (pipelineId == null) {
            return Map.of();
        }
        Sample sample = samples.get(pipelineId);
        if (sample == null) {
            return Map.of();
        }
        Map<Integer, Long> next = new HashMap<>();
        for (Map.Entry<Integer, Long> entry : sample.appliedOffset.entrySet()) {
            if (entry.getValue() != null) {
                next.put(entry.getKey(), entry.getValue() + 1L);
            }
        }
        return next;
    }

    public long taskId(long pipelineId) {
        Sample sample = samples.get(pipelineId);
        return sample == null ? 0L : sample.taskId;
    }

    /** Pipeline ids that currently have a sample (for metric binding / scrape). */
    public Iterable<Long> pipelineIds() {
        return samples.keySet();
    }

    /** Immutable view of the two pipeline positions and when each last moved. */
    public record Positions(String captured, long capturedAtMs, String applied, long appliedAtMs) {
    }

    public record Lags(long captureLagMs, long replayLagMs, long endToEndLagMs, long replayBacklog) {
    }

    public void clear(Long pipelineId) {
        if (pipelineId != null) {
            samples.remove(pipelineId);
        }
    }

    /**
     * @deprecated tests should use {@link LagComputations}; kept for a few call sites during the
     *     transition.
     */
    static Long lagMs(Sample sample, long nowMs) {
        if (sample == null) {
            return null;
        }
        return LagComputations.endToEndOrUnknown(sample.inputs(null), nowMs);
    }

    private static final class Listener implements MigrationLagListener {
        private final Sample sample;

        private Listener(Sample sample) {
            this.sample = sample;
        }

        @Override
        public void onApplied(long sourceEventTimeMs, long appliedAtMs) {
            sample.eventTimeMs = Math.max(sourceEventTimeMs, sample.eventTimeMs);
            sample.appliedAtMs = appliedAtMs;
        }

        @Override
        public void onHeartbeat(long seenAtMs) {
            sample.heartbeatAtMs = seenAtMs;
        }

        @Override
        public void onCaptured(String position, long atMs) {
            sample.capturedPosition = position;
            sample.capturedAtMs = atMs;
        }

        @Override
        public void onCapturedEvent(String position, long sourceEventTimeMs, long atMs) {
            sample.capturedPosition = position;
            sample.capturedAtMs = atMs;
            if (sourceEventTimeMs > 0L) {
                sample.capturedEventTimeMs = Math.max(sourceEventTimeMs, sample.capturedEventTimeMs);
                sample.capturedEventAtMs = atMs;
            }
        }

        @Override
        public void onAppliedPosition(String position, long atMs) {
            sample.appliedPosition = position;
            sample.appliedPositionAtMs = atMs;
        }

        @Override
        public void onProduced(int partition, long offset, long createTimeMs) {
            sample.kafkaMode = true;
            sample.producedOffset.merge(partition, offset, Math::max);
            if (createTimeMs > 0L) {
                sample.producedCreateTime.merge(partition, createTimeMs, Math::max);
            }
        }

        @Override
        public void onReplayed(int partition, long offset, long createTimeMs) {
            sample.kafkaMode = true;
            sample.appliedOffset.merge(partition, offset, Math::max);
            if (createTimeMs > 0L) {
                sample.appliedCreateTime.merge(partition, createTimeMs, Math::max);
            }
        }

        @Override
        public void onLogEnd(int partition, long endOffsetExclusive, long atMs) {
            sample.kafkaMode = true;
            sample.logEndExclusive.merge(partition, endOffsetExclusive, Math::max);
        }
    }

    /** Mutable, thread-safe-enough sample: writers are capture/replay threads; readers copy on read. */
    static final class Sample {
        volatile long taskId;
        volatile long eventTimeMs;
        volatile long appliedAtMs;
        volatile long heartbeatAtMs;
        volatile String capturedPosition;
        volatile long capturedAtMs;
        volatile String appliedPosition;
        volatile long appliedPositionAtMs;
        volatile long capturedEventTimeMs;
        volatile long capturedEventAtMs;
        volatile boolean kafkaMode;
        final ConcurrentHashMap<Integer, Long> producedOffset = new ConcurrentHashMap<>();
        final ConcurrentHashMap<Integer, Long> producedCreateTime = new ConcurrentHashMap<>();
        final ConcurrentHashMap<Integer, Long> appliedOffset = new ConcurrentHashMap<>();
        final ConcurrentHashMap<Integer, Long> appliedCreateTime = new ConcurrentHashMap<>();
        final ConcurrentHashMap<Integer, Long> logEndExclusive = new ConcurrentHashMap<>();

        Sample() {
        }

        /** Lag-only constructor used by older unit tests. */
        Sample(long eventTimeMs, long appliedAtMs, long heartbeatAtMs) {
            this.eventTimeMs = eventTimeMs;
            this.appliedAtMs = appliedAtMs;
            this.heartbeatAtMs = heartbeatAtMs;
        }

        LagComputations.Inputs inputs(String sourcePosition) {
            return new LagComputations.Inputs(
                    eventTimeMs,
                    appliedAtMs,
                    heartbeatAtMs,
                    capturedPosition,
                    capturedAtMs,
                    appliedPosition,
                    appliedPositionAtMs,
                    capturedEventTimeMs,
                    capturedEventAtMs,
                    kafkaMode,
                    Map.copyOf(producedOffset),
                    Map.copyOf(producedCreateTime),
                    Map.copyOf(appliedOffset),
                    Map.copyOf(appliedCreateTime),
                    Map.copyOf(logEndExclusive),
                    sourcePosition);
        }
    }
}
