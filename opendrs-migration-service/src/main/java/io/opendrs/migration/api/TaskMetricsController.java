package io.opendrs.migration.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.opendrs.common.api.Response;
import io.opendrs.migration.api.response.TaskReplayMetricsResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;

/**
 * Task replay metrics: queries Prometheus's HTTP API so the SPA never talks to Prometheus
 * directly (no CORS, one place to add auth later). Rates and timings come from the engine
 * meters {@code opendrs_capture_events_total}, the {@code opendrs_sink_tx_exec_time} /
 * {@code opendrs_sink_tx_commit_time} timers (whose {@code _count} rate is the replay TPS)
 * and the {@code opendrs_sink_replay_lag_seconds} gauge, all tagged {@code task_id} and
 * {@code direction} — a bidirectional task's two pipelines appear as forward/reverse series.
 */
@RestController
@RequestMapping("/api/v1/migration/tasks")
public class TaskMetricsController {

    private static final Logger log = LoggerFactory.getLogger(TaskMetricsController.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Matches the Prometheus scrape interval; rate() looks back 6 steps to stay smooth. */
    private static final String RATE_WINDOW = "30s";
    private static final int DEFAULT_MINUTES = 15;
    private static final int DEFAULT_STEP = 5;
    private static final int MIN_STEP_SECONDS = 5;
    /** Preset windows stay fine-grained; a custom window may span days, so its step may be far wider. */
    private static final int MAX_PRESET_STEP_SECONDS = 120;
    private static final int MAX_CUSTOM_STEP_SECONDS = 3600;
    /** Prometheus hard-rejects range queries above this many points per series. */
    private static final long MAX_POINTS_PER_SERIES = 11_000;

    private final RestClient prometheus;

    public TaskMetricsController(@Value("${opendrs.metrics.prometheus-url:http://127.0.0.1:9090}") String prometheusUrl) {
        this.prometheus = RestClient.builder().baseUrl(prometheusUrl).build();
    }

    /**
     * Replay metrics over either a relative window ({@code minutes} back from now, the default)
     * or an absolute {@code start}/{@code end} range in epoch seconds ("自定义" in the UI).
     * An absolute range wins only when fully valid (positive, end after start, not in the future);
     * a partial or invalid one falls back to the relative window instead of failing the request.
     * {@code step} is saturated into [5s, 2min] for preset windows and [5s, 1h] for custom ones.
     */
    @GetMapping("/{id}/metrics/replay")
    public Response<TaskReplayMetricsResponse> replay(
            @PathVariable Long id,
            @RequestParam(defaultValue = "15") int minutes,
            @RequestParam(defaultValue = "5") int step,
            @RequestParam(required = false) Long start,
            @RequestParam(required = false) Long end) {
        long now = System.currentTimeMillis() / 1000;
        boolean custom = start != null && end != null && start > 0 && end > start && end <= now;
        if (!custom && (start != null || end != null)) {
            log.warn("Ignoring invalid custom range start={} end={} (now={}); falling back to minutes={}",
                    start, end, now, minutes);
        }
        long windowEnd = custom ? end : now;
        long windowStart = custom ? start : windowEnd - clamp(minutes, 1, 60, DEFAULT_MINUTES) * 60L;
        int stepMax = custom ? MAX_CUSTOM_STEP_SECONDS : MAX_PRESET_STEP_SECONDS;
        // Saturate into range (unlike minutes' fallback): an over-wide step from an old or
        // hand-rolled client becomes the widest usable one, never a silently tiny default.
        int stepSeconds = Math.max(MIN_STEP_SECONDS, Math.min(stepMax, step));
        // Prometheus rejects range queries above 11k points per series; widen the step instead of erroring.
        long stepFloor = (windowEnd - windowStart) / MAX_POINTS_PER_SERIES;
        if (stepSeconds < stepFloor) {
            stepSeconds = (int) stepFloor;
        }

        // Every series is queried per direction: forward always exists, reverse only when a
        // bidirectional pipeline records it — one-way tasks simply get no reverse samples,
        // which the frontend renders as "no reverse direction".
        Map<Long, Double> capturedFwd = queryRange(capturedPromql(id, "forward"), windowStart, windowEnd, stepSeconds);
        Map<Long, Double> capturedRev = queryRange(capturedPromql(id, "reverse"), windowStart, windowEnd, stepSeconds);
        Map<Long, Double> appliedFwd = queryRange(appliedPromql(id, "forward"), windowStart, windowEnd, stepSeconds);
        Map<Long, Double> appliedRev = queryRange(appliedPromql(id, "reverse"), windowStart, windowEnd, stepSeconds);
        Map<Long, Double> appliedRowsFwd = queryRange(appliedRowsPromql(id, "forward"), windowStart, windowEnd, stepSeconds);
        Map<Long, Double> appliedRowsRev = queryRange(appliedRowsPromql(id, "reverse"), windowStart, windowEnd, stepSeconds);
        Map<Long, Double> execMsFwd = queryRange(txAvgPromql("opendrs_sink_tx_exec_time", id, "forward"), windowStart, windowEnd, stepSeconds);
        Map<Long, Double> execMsRev = queryRange(txAvgPromql("opendrs_sink_tx_exec_time", id, "reverse"), windowStart, windowEnd, stepSeconds);
        Map<Long, Double> commitMsFwd = queryRange(txAvgPromql("opendrs_sink_tx_commit_time", id, "forward"), windowStart, windowEnd, stepSeconds);
        Map<Long, Double> commitMsRev = queryRange(txAvgPromql("opendrs_sink_tx_commit_time", id, "reverse"), windowStart, windowEnd, stepSeconds);
        Map<Long, Double> lagFwd = queryRange(lagPromql(id, "forward"), windowStart, windowEnd, stepSeconds);
        Map<Long, Double> lagRev = queryRange(lagPromql(id, "reverse"), windowStart, windowEnd, stepSeconds);

        List<Long> timestamps = Stream.of(capturedFwd, capturedRev, appliedFwd, appliedRev,
                        appliedRowsFwd, appliedRowsRev, execMsFwd, execMsRev, commitMsFwd, commitMsRev,
                        lagFwd, lagRev)
                .flatMap(series -> series.keySet().stream())
                .distinct()
                .sorted()
                .toList();

        boolean anyReverse = Stream.of(capturedRev, appliedRev, appliedRowsRev, execMsRev, commitMsRev, lagRev)
                .anyMatch(series -> !series.isEmpty());
        List<TaskReplayMetricsResponse.Point> points = new ArrayList<>(timestamps.size());
        for (long t : timestamps) {
            TaskReplayMetricsResponse.DirectionMetrics forward = new TaskReplayMetricsResponse.DirectionMetrics(
                    capturedFwd.get(t), appliedFwd.get(t), appliedRowsFwd.get(t),
                    execMsFwd.get(t), commitMsFwd.get(t), lagFwd.get(t));
            TaskReplayMetricsResponse.DirectionMetrics reverse = anyReverse
                    ? new TaskReplayMetricsResponse.DirectionMetrics(
                            capturedRev.get(t), appliedRev.get(t), appliedRowsRev.get(t),
                            execMsRev.get(t), commitMsRev.get(t), lagRev.get(t))
                    : null;
            points.add(new TaskReplayMetricsResponse.Point(t, forward, reverse));
        }
        return Response.success(new TaskReplayMetricsResponse(points));
    }

    private static String capturedPromql(Long id, String direction) {
        return "sum(rate(opendrs_capture_events_total{task_id=\"" + id + "\",direction=\"" + direction + "\"}["
                + RATE_WINDOW + "]))";
    }

    private static String appliedPromql(Long id, String direction) {
        return "sum(rate(opendrs_sink_tx_exec_time_seconds_count{task_id=\"" + id + "\",direction=\"" + direction
                + "\"}[" + RATE_WINDOW + "]))";
    }

    private static String appliedRowsPromql(Long id, String direction) {
        return "sum(rate(opendrs_sink_applied_rows_total{task_id=\"" + id + "\",direction=\"" + direction + "\"}["
                + RATE_WINDOW + "]))";
    }

    private static String lagPromql(Long id, String direction) {
        return "max(opendrs_sink_replay_lag_seconds{task_id=\"" + id + "\",direction=\"" + direction + "\"})";
    }

    /** Average per-transaction time in milliseconds: rate(_sum) / rate(_count) × 1000. */
    private static String txAvgPromql(String metric, Long id, String direction) {
        return "1000 * sum(rate(" + metric + "_seconds_sum{task_id=\"" + id + "\",direction=\"" + direction
                + "\"}[" + RATE_WINDOW + "]))"
                + " / sum(rate(" + metric + "_seconds_count{task_id=\"" + id + "\",direction=\"" + direction
                + "\"}[" + RATE_WINDOW + "]))";
    }

    /**
     * One query_range call; returns timestamp → value, or an empty map when Prometheus is
     * unreachable or answered with an error (the chart then shows "no data" instead of 500).
     * The body is read as a String and parsed by our own ObjectMapper: converters for
     * {@code JsonNode} are not guaranteed (e.g. {@code @WebMvcTest} slices), String always is.
     */
    private Map<Long, Double> queryRange(String promql, long start, long end, int stepSeconds) {
        Map<Long, Double> values = new TreeMap<>();
        String body;
        try {
            body = prometheus.get()
                    .uri(uriBuilder -> uriBuilder
                            .path("/api/v1/query_range")
                            // PromQL carries { } which UriTemplate would parse as variables;
                            // pass it as an explicit template value so it is encoded verbatim.
                            .queryParam("query", "{promql}")
                            .queryParam("start", start)
                            .queryParam("end", end)
                            .queryParam("step", stepSeconds + "s")
                            .build(promql))
                    .retrieve()
                    .body(String.class);
            JsonNode root = MAPPER.readTree(body);
            if (root == null || !"success".equals(root.path("status").asText())) {
                log.warn("Prometheus query_range not successful for {}: {}", promql, body);
                return values;
            }
            for (JsonNode series : root.path("data").path("result")) {
                for (JsonNode sample : series.path("values")) {
                    double value = sample.get(1).asDouble();
                    // 平均耗时 = sum/count，count 为 0 的时刻 Prometheus 返回 +Inf/NaN
                    if (!Double.isNaN(value) && !Double.isInfinite(value)) {
                        values.put(sample.get(0).asLong(), value);
                    }
                }
            }
        } catch (Exception ex) {
            log.warn("Prometheus query_range failed for {}: {}", promql, ex.getMessage());
            return values;
        }
        return values;
    }

    private static int clamp(int value, int min, int max, int fallback) {
        return value < min || value > max ? fallback : value;
    }
}
