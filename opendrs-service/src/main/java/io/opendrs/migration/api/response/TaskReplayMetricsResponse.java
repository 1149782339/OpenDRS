package io.opendrs.migration.api.response;

import java.util.List;

/**
 * Per-second source-capture / target-replay rates, per-transaction timings and replay lag
 * for one task, built from Prometheus query_range over the engine meters. Every sample is
 * split by physical direction: {@code forward} (the task's declared direction, writes land
 * on the target) is always present, {@code reverse} carries the swapped direction of a
 * bidirectional task and is null for one-way ones. Null values mean "no sample at that
 * timestamp" (e.g. the task was not running); the chart should render them as gaps.
 */
public record TaskReplayMetricsResponse(List<Point> points) {

    /**
     * @param t epoch seconds
     * @param forward metrics of the forward pipeline (writes land on the target endpoint)
     * @param reverse metrics of the reverse pipeline (writes land on the source endpoint);
     *     null for one-way tasks
     */
    public record Point(long t, DirectionMetrics forward, DirectionMetrics reverse) {
    }

    /**
     * @param captured source rows captured per second, null when unknown
     * @param applied transactions replayed per second, null when unknown
     * @param appliedRows rows replayed per second (one JDBC batch of N addBatch'd rows
     *     counts as N, never 1), null when unknown
     * @param execMs average per-transaction statement execution time in milliseconds
     * @param commitMs average per-transaction commit time in milliseconds
     * @param lag replay lag in seconds
     */
    public record DirectionMetrics(Double captured, Double applied, Double appliedRows,
            Double execMs, Double commitMs, Double lag) {
    }
}
