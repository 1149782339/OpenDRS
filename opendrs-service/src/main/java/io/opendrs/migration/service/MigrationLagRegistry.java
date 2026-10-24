package io.opendrs.migration.service;

import io.opendrs.common.progress.MigrationLagListener;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * Live lag gauge for running tasks: the capture path pushes the source timestamp of the newest
 * committed change, readers subtract {@code now} when they need a number.
 *
 * <p>Nothing here is persisted, on purpose. Lag is a gauge, not a record: a stored value keeps
 * looking healthy after the pipeline stopped, and after a restart the dispatcher re-runs the task
 * and the entry is rebuilt within seconds anyway.
 *
 * <p>The two timestamps come from different clocks — {@code eventTimeMs} from the source server,
 * {@code appliedAtMs} and {@code heartbeatAtMs} from this host — so a skewed source clock shows up
 * directly in the result. It is clamped at zero rather than hidden, because a large persistent
 * negative value is itself the diagnostic.
 */
@Component
public class MigrationLagRegistry {

    /**
     * How long without a committed batch before the source counts as quiet.
     *
     * <p>This is the idle detector, not the heartbeat: while the pipeline is behind, commits keep
     * arriving, so {@code now - appliedAt} stays small and the raw difference is the answer. Ten
     * seconds without a commit means the target has everything the connector has read — a shorter
     * window would call a big transaction "caught up", a longer one would let the number climb for
     * no reason (measured: heartbeats here arrive ~48s apart, so keying the reset on them alone made
     * the gauge ramp to 48s and then snap to zero).
     */
    static final long IDLE_WINDOW_MS = 10_000L;

    /**
     * How long a heartbeat stays believable.
     *
     * <p>Not {@code 3 × heartbeat.interval.ms}: Debezium emits a heartbeat only after the binlog
     * reader has been idle for that interval, so any other traffic on the same MySQL instance — the
     * metadata tables of this very service live there too — pushes heartbeats out well past the
     * configured 10s. Measured spacing under a busy binlog was ~48s, so two minutes leaves room
     * without letting a dead connector look healthy for long. A connector that really dies surfaces
     * as a failed task, not as a lag number.
     */
    static final long ALIVE_WINDOW_MS = 120_000L;

    private final Map<Long, Sample> samples = new ConcurrentHashMap<>();

    /** The observer handed to {@code DataMigrationParam}; binds the pipeline id so the engine never sees one. */
    public MigrationLagListener listenerFor(long pipelineId) {
        return new MigrationLagListener() {
            @Override
            public void onApplied(long sourceEventTimeMs, long appliedAtMs) {
                samples.compute(pipelineId, (id, previous) -> new Sample(
                        // Max, not last: a source clock that steps backwards must not make the
                        // gauge retreat, and batches are applied in order anyway.
                        Math.max(sourceEventTimeMs, previous == null ? 0L : previous.eventTimeMs()),
                        appliedAtMs,
                        previous == null ? 0L : previous.heartbeatAtMs(),
                        previous == null ? null : previous.capturedPosition(),
                        previous == null ? 0L : previous.capturedAtMs(),
                        previous == null ? null : previous.appliedPosition(),
                        previous == null ? 0L : previous.appliedPositionAtMs()));
            }

            @Override
            public void onHeartbeat(long seenAtMs) {
                samples.compute(pipelineId, (id, previous) -> previous == null
                        ? new Sample(0L, 0L, seenAtMs)
                        : new Sample(previous.eventTimeMs(), previous.appliedAtMs(), seenAtMs,
                                previous.capturedPosition(), previous.capturedAtMs(),
                                previous.appliedPosition(), previous.appliedPositionAtMs()));
            }

            @Override
            public void onCaptured(String position, long atMs) {
                samples.compute(pipelineId, (id, previous) -> previous == null
                        ? new Sample(0L, 0L, 0L, position, atMs, null, 0L)
                        : new Sample(previous.eventTimeMs(), previous.appliedAtMs(), previous.heartbeatAtMs(),
                                position, atMs,
                                previous.appliedPosition(), previous.appliedPositionAtMs()));
            }

            @Override
            public void onAppliedPosition(String position, long atMs) {
                samples.compute(pipelineId, (id, previous) -> previous == null
                        ? new Sample(0L, 0L, 0L, null, 0L, position, atMs)
                        : new Sample(previous.eventTimeMs(), previous.appliedAtMs(), previous.heartbeatAtMs(),
                                previous.capturedPosition(), previous.capturedAtMs(),
                                position, atMs));
            }
        };
    }

    /** Milliseconds the target is behind, or empty when this pipeline has nothing to measure yet. */
    public Optional<Long> lagMs(Long pipelineId) {
        if (pipelineId == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(lagMs(samples.get(pipelineId), System.currentTimeMillis()));
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
                sample.capturedPosition(),
                sample.capturedAtMs(),
                sample.appliedPosition(),
                sample.appliedPositionAtMs()));
    }

    /** Immutable view of the two pipeline positions and when each last moved. */
    public record Positions(String captured, long capturedAtMs, String applied, long appliedAtMs) {
    }

    public void clear(Long pipelineId) {
        if (pipelineId != null) {
            samples.remove(pipelineId);
        }
    }

    /**
     * The rule, isolated from the clock so it can be tested.
     *
     * <p>{@code now - eventTime} is the definition, but on its own it grows forever when the source
     * is simply quiet — the classic way this gauge lies. Two facts fix that, and they answer
     * different questions:
     *
     * <ul>
     *   <li>no commit for {@link #IDLE_WINDOW_MS} ⇒ nothing is pending, so the target is caught up;
     *   <li>a heartbeat within {@link #ALIVE_WINDOW_MS} ⇒ the connector is still reading, so that
     *       silence means an idle source rather than a dead pipeline.
     * </ul>
     *
     * <p>Both are required before reporting zero: without the heartbeat, silence would also describe
     * a connector that stopped reading, and then the raw difference is exactly what an operator
     * needs to watch.
     */
    static Long lagMs(Sample sample, long nowMs) {
        if (sample == null) {
            return null;
        }
        // "No heartbeat yet" is not "heartbeats stopped": the engine always configures them, so the
        // only way to have none is a run that just started, and the first one is up to ~50s away.
        // Treating that as dead made the gauge ramp for a minute after every restart.
        boolean alive = sample.heartbeatAtMs() <= 0
                || nowMs - sample.heartbeatAtMs() <= ALIVE_WINDOW_MS;
        if (alive && (sample.eventTimeMs() <= 0 || nowMs - sample.appliedAtMs() > IDLE_WINDOW_MS)) {
            return 0L;
        }
        if (sample.eventTimeMs() <= 0) {
            // Incremental just started: no change seen yet, so there is nothing to be behind on.
            return alive ? 0L : null;
        }
        return Math.max(0L, nowMs - sample.eventTimeMs());
    }

    /** Immutable so the HTTP thread never sees a half-updated pair. */
    record Sample(long eventTimeMs, long appliedAtMs, long heartbeatAtMs,
                  String capturedPosition, long capturedAtMs,
                  String appliedPosition, long appliedPositionAtMs) {

        /** Lag-only samples (tests, heartbeats before any position arrived). */
        Sample(long eventTimeMs, long appliedAtMs, long heartbeatAtMs) {
            this(eventTimeMs, appliedAtMs, heartbeatAtMs, null, 0L, null, 0L);
        }
    }
}
