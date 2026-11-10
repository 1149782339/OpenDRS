package io.opendrs.migration.metrics;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.opendrs.migration.service.MigrationLagRegistry;
import jakarta.annotation.PostConstruct;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * Prometheus gauges for the three lag numbers plus replay record backlog. The historical
 * {@code opendrs_sink_replay_lag_seconds} name is kept as an alias of end-to-end lag (with
 * the new catch-up-to-zero rule) so the existing TaskMonitor chart stays the main number.
 */
@Component
public class MigrationLagMetrics {

    private final MeterRegistry meters;
    private final MigrationLagRegistry lags;
    private final Set<Long> bound = ConcurrentHashMap.newKeySet();

    public MigrationLagMetrics(MeterRegistry meters, MigrationLagRegistry lags) {
        this.meters = meters;
        this.lags = lags;
    }

    @PostConstruct
    void bindRegistry() {
        lags.setMeterBinder(this::bind);
        for (Long pipelineId : lags.pipelineIds()) {
            bind(pipelineId, lags.taskId(pipelineId));
        }
    }

    void bind(long pipelineId, long taskId) {
        if (!bound.add(pipelineId)) {
            return;
        }
        Tags tags = Tags.of(
                "pipeline_id", String.valueOf(pipelineId),
                "task_id", String.valueOf(taskId),
                "direction", "forward");
        Gauge.builder("opendrs.end.to.end.lag.seconds", () -> seconds(pipelineId, Kind.END_TO_END))
                .description("End-to-end lag: now minus source.ts_ms of the last applied event; 0 when caught up")
                .tags(tags)
                .register(meters);
        Gauge.builder("opendrs.capture.lag.seconds", () -> seconds(pipelineId, Kind.CAPTURE))
                .description("Capture lag: now minus source.ts_ms of the last captured event; 0 when idle/caught up")
                .tags(tags)
                .register(meters);
        Gauge.builder("opendrs.replay.lag.seconds", () -> seconds(pipelineId, Kind.REPLAY))
                .description("Replay lag: last produced CreateTime minus last applied CreateTime; 0 when caught up")
                .tags(tags)
                .register(meters);
        Gauge.builder("opendrs.replay.backlog.records", () -> backlog(pipelineId))
                .description("Kafka record backlog (log-end minus last applied offset) across partitions")
                .tags(tags)
                .register(meters);
        // Keep the historical name as the main (end-to-end) number for TaskMonitor.
        Gauge.builder("opendrs.sink.replay.lag.seconds", () -> seconds(pipelineId, Kind.END_TO_END))
                .description("End-to-end lag (alias of opendrs_end_to_end_lag_seconds)")
                .tags(tags)
                .register(meters);
    }

    private double seconds(long pipelineId, Kind kind) {
        return lags.lags(pipelineId)
                .map(value -> switch (kind) {
                    case CAPTURE -> value.captureLagMs();
                    case REPLAY -> value.replayLagMs();
                    case END_TO_END -> value.endToEndLagMs();
                })
                .orElse(0L) / 1000.0;
    }

    private double backlog(long pipelineId) {
        return lags.lags(pipelineId).map(MigrationLagRegistry.Lags::replayBacklog).orElse(0L);
    }

    private enum Kind {
        CAPTURE, REPLAY, END_TO_END
    }
}
