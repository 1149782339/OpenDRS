package io.opendrs.migration.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.opendrs.common.metadata.DatabaseType;
import io.opendrs.engine.core.MigrationEngine;
import io.opendrs.engine.core.PrecheckParam;
import io.opendrs.engine.core.PrecheckTask;
import io.opendrs.engine.core.TaskState;
import io.opendrs.engine.precheck.CheckGroup;
import io.opendrs.engine.precheck.CheckIds;
import io.opendrs.engine.precheck.CheckMeta;
import io.opendrs.engine.precheck.Evaluation;
import io.opendrs.engine.precheck.PrecheckItem;
import io.opendrs.engine.precheck.PrecheckResults;
import io.opendrs.engine.precheck.Severity;
import io.opendrs.engine.precheck.Side;
import io.opendrs.engine.precheck.Verdict;
import io.opendrs.engine.precheck.profile.MysqlToPgProfile;
import io.opendrs.migration.domain.ConnectionInfo;
import io.opendrs.migration.domain.DbType;
import io.opendrs.migration.domain.MigrationPhase;
import io.opendrs.migration.mapper.ConnectionInfoMapper;
import io.opendrs.migration.mapper.MigrationTaskMapper;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The precheck state machine: phases, CAS re-runs, the persisted checklist and the error column.
 *
 * <p>The engine is mocked on purpose. What is tested here is the service's behaviour around a run -
 * when it may start, what it stores, when the task is admitted - not which checks exist; that
 * belongs to {@code engine-precheck} and is covered there against in-memory endpoints.
 */
@SpringBootTest
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class MigrationTaskPrecheckApiTest {

    private static final String BASE = "/api/v1/migration/tasks";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JsonMapper jsonMapper;

    @Autowired
    private MigrationTaskMapper taskMapper;

    @Autowired
    private ConnectionInfoMapper connectionInfoMapper;

    @MockitoBean
    private MigrationEngine engine;

    private long mysqlSourceId;

    private long postgresTargetId;

    private long oracleSourceId;

    private long mysqlTargetId;

    private PrecheckResults canned;

    @BeforeEach
    void stubEngine() {
        Mockito.reset(engine);
        mysqlSourceId = dataSource(
                "precheck-mysql-source", DbType.MYSQL, "10.0.0.2", 3306, "hr", "cdc", "secret-source");
        postgresTargetId = dataSource(
                "precheck-postgres-target", DbType.POSTGRESQL, "10.0.0.3", 5432, "appdb", "drs", "secret-target");
        oracleSourceId = dataSource(
                "precheck-oracle-source", DbType.ORACLE, "10.0.0.1", 1521, "ORCL", "cdc", "secret-source");
        mysqlTargetId = dataSource(
                "precheck-mysql-target", DbType.MYSQL, "10.0.0.2", 3306, "hr", "drs", "secret-target");

        canned = canned(true);
        when(engine.supportsPrecheck(any(DatabaseType.class), any(DatabaseType.class))).thenReturn(true);
        when(engine.precheck(any(PrecheckParam.class))).thenAnswer(invocation -> {
            PrecheckParam param = invocation.getArgument(0);
            publish(param.progressListener(), canned);
            return completed(canned);
        });
    }

    @Test
    void precheckFromCreatedLeavesTaskReadyToStart() throws Exception {
        long id = createMysqlToPostgres("precheck-ok");

        mockMvc.perform(post(BASE + "/" + id + "/precheck"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1000))
                .andExpect(jsonPath("$.message").value("success"));

        JsonNode done = awaitPrecheckFinished(id);
        assertThat(done.path("ok").asBoolean()).isTrue();
        assertThat(done.path("phase").asString()).isEqualTo("PRECHECKED");
        // Precheck only unlocks the task: the migration is started by the wizard's 任务确认 step.
        assertThat(done.path("state").isNull()).isTrue();
        assertThat(done.path("profileId").asString()).isEqualTo(MysqlToPgProfile.ID);
        assertThat(done.path("items").size()).isEqualTo(2);
        assertThat(done.path("runningId").isNull()).isTrue();

        mockMvc.perform(get(BASE + "/" + id + "/precheck"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1000))
                .andExpect(jsonPath("$.data.ok").value(true))
                .andExpect(jsonPath("$.data.phase").value("PRECHECKED"))
                .andExpect(jsonPath("$.data.state").value(nullValue()))
                .andExpect(jsonPath("$.data.items[?(@.id=='source.connection' && @.verdict=='PASS')]").isNotEmpty())
                .andExpect(jsonPath("$.data.items[?(@.id=='source.connection')].state").value("COMPLETED"))
                .andExpect(jsonPath("$.data.items[?(@.id=='source.connection')].group").value("NETWORK"));

        var stored = taskMapper.findById(id);
        assertThat(stored.getPhase()).isEqualTo(MigrationPhase.PRECHECKED);
        assertThat(stored.getState()).isNull();
        assertThat(stored.getErrorMessage()).isNull();
        assertThat(stored.getPrecheckResultsJson()).isNotNull();
        assertThat(stored.getPrecheckResultsJson().items()).hasSize(2);
        // The scheduled dispatcher only picks STARTING/RUNNING, so an idle PRECHECKED row is
        // never attached to a coordinator thread behind the user's back.
        assertThat(taskMapper.findDispatchable()).noneMatch(candidate -> candidate.getId().equals(id));

        // Confirming in the wizard is what actually starts the pipeline.
        mockMvc.perform(post(BASE + "/" + id + "/start"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1000))
                .andExpect(jsonPath("$.data.phase").value("SCHEMA_MIGRATION"))
                .andExpect(jsonPath("$.data.state").value("STARTING"));
    }

    @Test
    void postPrecheckReturnsWhileTheEngineIsStillWorking() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch published = new CountDownLatch(1);
        when(engine.precheck(any(PrecheckParam.class))).thenAnswer(invocation -> {
            PrecheckParam param = invocation.getArgument(0);
            Thread worker = new Thread(() -> {
                entered.countDown();
                awaitQuietly(release);
                publish(param.progressListener(), canned);
                published.countDown();
            }, "fake-engine");
            worker.setDaemon(true);
            worker.start();
            // The handle must not report completion before the final publish landed, or the
            // service can commit phase=PRECHECKED while the stored results are still the empty
            // ones and the last read sees ok=false (same race as checklistIsReadable...).
            return awaiting(canned, published);
        });
        long id = createMysqlToPostgres("precheck-async");

        try {
            long startedAt = System.nanoTime();
            mockMvc.perform(post(BASE + "/" + id + "/precheck"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(1000))
                    .andExpect(jsonPath("$.data.ok").value(false))
                    .andExpect(jsonPath("$.data.phase").value("PRECHECKING"));
            assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)).isLessThan(1500);
            assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();

            // A second click while the run is in flight returns the current state instead of
            // starting a competing run.
            mockMvc.perform(post(BASE + "/" + id + "/precheck"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(1000))
                    .andExpect(jsonPath("$.data.phase").value("PRECHECKING"));
        } finally {
            release.countDown();
        }
        JsonNode done = awaitPrecheckFinished(id);
        assertThat(done.path("ok").asBoolean()).isTrue();
        assertThat(done.path("phase").asString()).isEqualTo("PRECHECKED");
        assertThat(done.path("state").isNull()).isTrue();
    }

    /** The engine publishes the whole checklist first and then row by row; both must be readable. */
    @Test
    void checklistIsReadableWhileTheRunIsStillGoing() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch published = new CountDownLatch(1);
        PrecheckResults plan = PrecheckResults.planned(MysqlToPgProfile.ID, List.of(
                PrecheckItem.pending(
                        new CheckMeta(CheckIds.SOURCE_CONNECTION, "源库连接是否成功", CheckGroup.NETWORK, Side.SOURCE),
                        Severity.BLOCKER, 0).running(),
                PrecheckItem.pending(
                        new CheckMeta(CheckIds.SOURCE_LOG_BIN, "源库 binlog 是否开启", CheckGroup.PARAMETER, Side.SOURCE),
                        Severity.BLOCKER, 1)))
                .withRunning(CheckIds.SOURCE_CONNECTION);
        when(engine.precheck(any(PrecheckParam.class))).thenAnswer(invocation -> {
            PrecheckParam param = invocation.getArgument(0);
            Thread worker = new Thread(() -> {
                publish(param.progressListener(), plan);
                awaitQuietly(release);
                publish(param.progressListener(), canned);
                published.countDown();
            }, "fake-engine");
            worker.setDaemon(true);
            worker.start();
            // The run is only finished once the last publish landed: a handle that returns
            // immediately would let the service mark PRECHECKED while the worker is still to
            // write the final checklist, and the stored row would flip back to "running".
            return awaiting(canned, published);
        });
        long id = createMysqlToPostgres("precheck-stream");

        try {
            mockMvc.perform(post(BASE + "/" + id + "/precheck")).andExpect(status().isOk());
            JsonNode running = awaitItems(id);
            assertThat(running.path("items").size()).isEqualTo(2);
            assertThat(running.path("runningId").asString()).isEqualTo(CheckIds.SOURCE_CONNECTION);
            // Every row is on screen from the start: one says "checking", the other waits.
            assertThat(running.path("items").get(0).path("state").asString()).isEqualTo("RUNNING");
            assertThat(running.path("items").get(1).path("state").asString()).isEqualTo("PENDING");
            assertThat(running.path("ok").asBoolean()).isFalse();
        } finally {
            release.countDown();
        }
        JsonNode done = awaitPrecheckFinished(id);
        assertThat(done.path("runningId").isNull()).isTrue();
        assertThat(done.path("ok").asBoolean()).isTrue();
    }

    @Test
    void failedBlockerLeavesPrecheckingFailedWithTheCheckNamed() throws Exception {
        canned = canned(false);
        long id = createMysqlToPostgres("precheck-fail");

        mockMvc.perform(post(BASE + "/" + id + "/precheck"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1000));

        JsonNode done = awaitPrecheckFinished(id);
        assertThat(done.path("ok").asBoolean()).isFalse();
        assertThat(done.path("phase").asString()).isEqualTo("PRECHECKING");
        assertThat(done.path("state").asString()).isEqualTo("FAILED");

        mockMvc.perform(get(BASE + "/" + id + "/precheck"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.ok").value(false))
                .andExpect(jsonPath("$.data.phase").value("PRECHECKING"))
                .andExpect(jsonPath("$.data.state").value("FAILED"))
                .andExpect(jsonPath("$.data.items[?(@.verdict=='FAIL')].id").value(CheckIds.SOURCE_CONNECTION));

        var stored = taskMapper.findById(id);
        assertThat(stored.getPhase()).isEqualTo(MigrationPhase.PRECHECKING);
        assertThat(stored.getState()).isEqualTo(TaskState.FAILED);
        assertThat(stored.getErrorMessage()).contains(CheckIds.SOURCE_CONNECTION);
        assertThat(stored.getErrorMessage()).contains("connection refused");

        mockMvc.perform(get(BASE + "/" + id + "/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.phase").value("PRECHECKING"))
                .andExpect(jsonPath("$.data.state").value("FAILED"))
                .andExpect(jsonPath("$.data.error").isNotEmpty());
    }

    /** A failed warning does not block: the user accepts it by moving to the next step. */
    @Test
    void failedWarningIsStillAdmitted() throws Exception {
        canned = PrecheckResults.planned(MysqlToPgProfile.ID, List.of(
                completed(CheckIds.SOURCE_CONNECTION, Severity.BLOCKER, Verdict.PASS, 0),
                completed(CheckIds.SOURCE_PRIMARY_KEY, Severity.WARNING, Verdict.FAIL, 1)));
        long id = createMysqlToPostgres("precheck-warning");

        mockMvc.perform(post(BASE + "/" + id + "/precheck")).andExpect(status().isOk());

        JsonNode done = awaitPrecheckFinished(id);
        assertThat(done.path("ok").asBoolean()).isTrue();
        assertThat(done.path("phase").asString()).isEqualTo("PRECHECKED");
    }

    @Test
    void precheckMissingTaskReturns1002() throws Exception {
        mockMvc.perform(post(BASE + "/999999/precheck"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1002))
                .andExpect(jsonPath("$.message").value("Task not found: 999999"));
        mockMvc.perform(get(BASE + "/999999/precheck"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1002));
    }

    @Test
    void getPrecheckOnCreatedReturnsEmptyChecklist() throws Exception {
        long id = createMysqlToPostgres("precheck-get-created");
        mockMvc.perform(get(BASE + "/" + id + "/precheck"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1000))
                .andExpect(jsonPath("$.data.ok").value(false))
                .andExpect(jsonPath("$.data.phase").value("CREATED"))
                .andExpect(jsonPath("$.data.items").isArray())
                .andExpect(jsonPath("$.data.items").isEmpty());
    }

    @Test
    void precheckWhileTaskInFlightReturns1003() throws Exception {
        long id = createMysqlToPostgres("precheck-starting");
        mockMvc.perform(post(BASE + "/" + id + "/precheck")).andExpect(status().isOk());
        JsonNode done = awaitPrecheckFinished(id);
        assertThat(done.path("state").isNull()).isTrue();

        // STOPPING is the one in-flight state the scheduled dispatcher will not pick up, so this
        // assertion cannot race a coordinator thread.
        taskMapper.updatePhaseState(id, MigrationPhase.SCHEMA_MIGRATION, TaskState.STOPPING);
        mockMvc.perform(post(BASE + "/" + id + "/precheck"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1003));
    }

    @Test
    void precheckRerunFromFailedThenFromStopped() throws Exception {
        long id = createMysqlToPostgres("precheck-rerun");

        canned = canned(false);
        mockMvc.perform(post(BASE + "/" + id + "/precheck")).andExpect(status().isOk());
        JsonNode failed = awaitPrecheckFinished(id);
        assertThat(failed.path("ok").asBoolean()).isFalse();
        assertThat(failed.path("state").asString()).isEqualTo("FAILED");

        canned = canned(true);
        mockMvc.perform(post(BASE + "/" + id + "/precheck"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1000));
        JsonNode recovered = awaitPrecheckFinished(id);
        assertThat(recovered.path("ok").asBoolean()).isTrue();
        assertThat(recovered.path("phase").asString()).isEqualTo("PRECHECKED");
        assertThat(recovered.path("state").isNull()).isTrue();
        assertThat(taskMapper.findById(id).getErrorMessage()).isNull();

        // A run that was stopped before it left PRECHECKED is still re-checkable.
        taskMapper.updatePhaseState(id, MigrationPhase.PRECHECKED, TaskState.STOPPED);
        mockMvc.perform(post(BASE + "/" + id + "/precheck")).andExpect(status().isOk());
        JsonNode rerun = awaitPrecheckFinished(id);
        assertThat(rerun.path("ok").asBoolean()).isTrue();
        assertThat(rerun.path("state").isNull()).isTrue();
    }

    @Test
    void recheckFromPrecheckedIdleIsAllowed() throws Exception {
        long id = createMysqlToPostgres("precheck-idle-recheck");

        mockMvc.perform(post(BASE + "/" + id + "/precheck")).andExpect(status().isOk());
        assertThat(awaitPrecheckFinished(id).path("state").isNull()).isTrue();

        // This is what the 重新校验 button on the 预检查 step does before the user confirms.
        mockMvc.perform(post(BASE + "/" + id + "/precheck"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1000));
        JsonNode again = awaitPrecheckFinished(id);
        assertThat(again.path("ok").asBoolean()).isTrue();
        assertThat(again.path("state").isNull()).isTrue();
    }

    @Test
    void precheckFromStuckPrecheckingReturnsCurrentWithoutSecondRun() throws Exception {
        long id = createMysqlToPostgres("precheck-stuck");
        taskMapper.updatePhaseState(id, MigrationPhase.PRECHECKING, null);

        mockMvc.perform(post(BASE + "/" + id + "/precheck"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1000))
                .andExpect(jsonPath("$.data.ok").value(false))
                .andExpect(jsonPath("$.data.phase").value("PRECHECKING"));
        assertThat(taskMapper.findById(id).getPhase()).isEqualTo(MigrationPhase.PRECHECKING);
        assertThat(taskMapper.findById(id).getState()).isNull();
    }

    @Test
    void precheckUnsupportedDirectionReturns1001() throws Exception {
        when(engine.supportsPrecheck(DatabaseType.ORACLE, DatabaseType.MYSQL)).thenReturn(false);
        MvcResult created = mockMvc.perform(post(BASE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(oracleMysqlBody("precheck-oracle")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1000))
                .andReturn();
        long id = jsonMapper.readTree(created.getResponse().getContentAsString()).path("data").path("id").asLong();

        mockMvc.perform(post(BASE + "/" + id + "/precheck"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1001))
                .andExpect(jsonPath("$.message").value("No precheck registered for ORACLE → MYSQL"));
        assertThat(taskMapper.findById(id).getPhase()).isEqualTo(MigrationPhase.CREATED);
        assertThat(taskMapper.findById(id).getState()).isNull();
    }

    private static PrecheckResults canned(boolean ok) {
        return PrecheckResults.planned(MysqlToPgProfile.ID, List.of(
                completed(CheckIds.SOURCE_CONNECTION, Severity.BLOCKER,
                        ok ? Verdict.PASS : Verdict.FAIL, 0),
                completed(CheckIds.TARGET_CONNECTION, Severity.BLOCKER, Verdict.PASS, 1)));
    }

    private static PrecheckItem completed(String id, Severity severity, Verdict verdict, int order) {
        Side side = id.startsWith("source") ? Side.SOURCE : Side.TARGET;
        PrecheckItem pending = PrecheckItem.pending(
                new CheckMeta(id, id, CheckGroup.NETWORK, side), severity, order);
        if (verdict == Verdict.FAIL) {
            return pending.completed(Evaluation.fail(
                    "connection refused", "10.0.0.2:3306", "reachable", "fix the network"));
        }
        return pending.completed(Evaluation.pass());
    }

    private static PrecheckTask completed(PrecheckResults results) {
        PrecheckTask task = Mockito.mock(PrecheckTask.class);
        when(task.results()).thenReturn(results);
        return task;
    }

    /** A handle that only reports completion once the fake worker has published its last state. */
    private static PrecheckTask awaiting(PrecheckResults results, CountDownLatch published)
            throws InterruptedException {
        PrecheckTask task = completed(results);
        doAnswer(invocation -> {
            published.await();
            return null;
        }).when(task).await();
        return task;
    }

    private static void publish(Consumer<PrecheckResults> listener, PrecheckResults results) {
        if (listener != null) {
            listener.accept(results);
        }
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("test latch timed out");
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(ex);
        }
    }

    private long dataSource(
            String name, DbType type, String host, int port, String database, String username, String password) {
        ConnectionInfo existing = connectionInfoMapper.findByName(name);
        if (existing != null) {
            return existing.getId();
        }
        ConnectionInfo connection = new ConnectionInfo();
        connection.setName(name);
        connection.setType(type);
        connection.setHost(host);
        connection.setPort(port);
        connection.setDbName(database);
        connection.setUsername(username);
        connection.setPassword(password);
        connection.setExtra(Map.of());
        connectionInfoMapper.insert(connection);
        return connection.getId();
    }

    private JsonNode awaitItems(long id) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            JsonNode data = current(id);
            if (data.path("items").size() > 0) {
                return data;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("precheck never published its checklist for task " + id);
    }

    private JsonNode awaitPrecheckFinished(long id) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        JsonNode last = null;
        while (System.nanoTime() < deadline) {
            last = current(id);
            String phase = last.path("phase").asString();
            String state = last.path("state").isNull() ? null : last.path("state").asString();
            if ("PRECHECKED".equals(phase)
                    || MigrationPhase.SCHEMA_MIGRATION.name().equals(phase)
                    || MigrationPhase.FULL.name().equals(phase)
                    || MigrationPhase.INCREMENTAL.name().equals(phase)
                    || "FAILED".equals(state)) {
                return last;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("precheck did not finish for task " + id + ": " + last);
    }

    private JsonNode current(long id) throws Exception {
        ResultActions action = mockMvc.perform(get(BASE + "/" + id + "/precheck")).andExpect(status().isOk());
        MvcResult result = action.andReturn();
        return jsonMapper.readTree(result.getResponse().getContentAsString()).path("data");
    }

    private long createMysqlToPostgres(String name) throws Exception {
        MvcResult result = mockMvc.perform(post(BASE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mysqlPostgresBody(name)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1000))
                .andReturn();
        JsonNode root = jsonMapper.readTree(result.getResponse().getContentAsString());
        return root.path("data").path("id").asLong();
    }

    private String mysqlPostgresBody(String name) {
        return """
                {
                  "name": "%s",
                  "mode": "FULL_AND_INCREMENTAL",
                  "sourceConnectionId": %d,
                  "targetConnectionId": %d,
                  "options": {
                    "selection": {
                      "mode": "TABLE",
                      "schemas": [],
                      "tables": [
                        { "schema": "hr", "table": "emp" },
                        { "schema": "hr", "table": "dept" }
                      ],
                      "excludeSchemas": [],
                      "excludeTables": [],
                      "mappings": {
                        "schema": [ { "source": "hr", "target": "hr" } ]
                      }
                    }
                  }
                }
                """.formatted(name, mysqlSourceId, postgresTargetId);
    }

    private String oracleMysqlBody(String name) {
        return """
                {
                  "name": "%s",
                  "mode": "FULL_AND_INCREMENTAL",
                  "sourceConnectionId": %d,
                  "targetConnectionId": %d,
                  "options": {
                    "selection": {
                      "mode": "TABLE",
                      "schemas": [],
                      "tables": [ { "schema": "HR", "table": "EMPLOYEES" } ],
                      "excludeSchemas": [],
                      "excludeTables": []
                    }
                  }
                }
                """.formatted(name, oracleSourceId, mysqlTargetId);
    }
}
