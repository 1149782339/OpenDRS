package io.opendrs.migration.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import io.opendrs.common.api.Response;
import io.opendrs.migration.api.response.TaskReplayMetricsResponse;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Which time window and step actually reach Prometheus for preset vs custom ("自定义") windows.
 * A stub Prometheus records the query params of every query_range call and answers with one fixed
 * sample, so response assembly is exercised end to end without a real Prometheus.
 */
class TaskMetricsControllerTest {

    private HttpServer prometheus;
    private final List<Map<String, String>> queries = new ArrayList<>();
    private TaskMetricsController controller;

    @BeforeEach
    void startStub() throws IOException {
        prometheus = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        prometheus.createContext("/api/v1/query_range", exchange -> {
            queries.add(parseQuery(exchange.getRequestURI().getRawQuery()));
            String body = """
                    {"status":"success","data":{"result":[{"metric":{},"values":[[100,"1.0"]]}]}}
                    """;
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        prometheus.start();
        controller = new TaskMetricsController("http://127.0.0.1:" + prometheus.getAddress().getPort());
    }

    @AfterEach
    void stopStub() {
        prometheus.stop(0);
    }

    @Test
    void customRangeIsForwardedVerbatim() {
        long now = System.currentTimeMillis() / 1000;
        long start = now - 3600;
        long end = now - 60;

        Response<TaskReplayMetricsResponse> response = controller.replay(7L, 15, 5, start, end);

        assertThat(queries).hasSize(12);
        assertThat(queries).allSatisfy(q -> assertThat(q)
                .containsEntry("start", String.valueOf(start))
                .containsEntry("end", String.valueOf(end))
                .containsEntry("step", "5s"));
        assertThat(response.getCode()).isEqualTo(1000);
        assertThat(response.getData().points()).hasSize(1);
        assertThat(response.getData().points().getFirst().t()).isEqualTo(100L);
    }

    @Test
    void withoutCustomRangeMinutesDerivesTheWindow() {
        long before = System.currentTimeMillis() / 1000;

        controller.replay(7L, 30, 10, null, null);

        long after = System.currentTimeMillis() / 1000;
        assertThat(queries).hasSize(12);
        assertThat(queries).allSatisfy(q -> {
            long start = Long.parseLong(q.get("start"));
            long end = Long.parseLong(q.get("end"));
            assertThat(start).isBetween(before - 1805, after - 1795);
            assertThat(end).isBetween(before - 5, after + 5);
            assertThat(q).containsEntry("step", "10s");
        });
    }

    @Test
    void invalidCustomRangesFallBackToMinutes() {
        long now = System.currentTimeMillis() / 1000;

        queries.clear();
        controller.replay(7L, 15, 5, now - 100, now - 100); // end == start
        assertLastWindowFallsBackToMinutes();

        queries.clear();
        controller.replay(7L, 15, 5, now - 100, now + 100); // end in the future
        assertLastWindowFallsBackToMinutes();

        queries.clear();
        controller.replay(7L, 15, 5, now - 100, null); // only half of the range
        assertLastWindowFallsBackToMinutes();
    }

    private void assertLastWindowFallsBackToMinutes() {
        long before = System.currentTimeMillis() / 1000;
        assertThat(queries).hasSize(12);
        assertThat(queries).allSatisfy(q -> {
            long start = Long.parseLong(q.get("start"));
            long end = Long.parseLong(q.get("end"));
            // 15-minute default window, tolerant of the seconds ticked between the calls.
            assertThat(start).isBetween(before - 905, before - 890);
            assertThat(end).isBetween(before - 5, before + 5);
            assertThat(q).containsEntry("step", "5s");
        });
    }

    @Test
    void customStepIsClampedAtOneHour() {
        long now = System.currentTimeMillis() / 1000;

        controller.replay(7L, 15, 99999, now - 3600, now);

        assertThat(queries).allSatisfy(q -> assertThat(q).containsEntry("step", "3600s"));
    }

    @Test
    void presetStepIsClampedAtTwoMinutes() {
        controller.replay(7L, 15, 99999, null, null);

        assertThat(queries).allSatisfy(q -> assertThat(q).containsEntry("step", "120s"));
    }

    @Test
    void stepIsWidenedPastThePrometheusPointLimit() {
        long now = System.currentTimeMillis() / 1000;
        long tenDays = 10 * 24 * 60 * 60L;

        controller.replay(7L, 15, 5, now - tenDays, now);

        // 864000s / 11000 points = 78s floor; the requested 5s step would be rejected by Prometheus.
        assertThat(queries).allSatisfy(q -> assertThat(q).containsEntry("step", "78s"));
    }

    private static Map<String, String> parseQuery(String rawQuery) {
        Map<String, String> params = new LinkedHashMap<>();
        for (String pair : rawQuery.split("&")) {
            String[] kv = pair.split("=", 2);
            params.put(urlDecode(kv[0]), kv.length > 1 ? urlDecode(kv[1]) : "");
        }
        return params;
    }

    private static String urlDecode(String value) {
        return URLDecoder.decode(value, StandardCharsets.UTF_8);
    }
}
