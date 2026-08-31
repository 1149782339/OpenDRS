package io.opendrs.migration.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.opendrs.jdbc.JdbcConnection;
import io.opendrs.jdbc.JdbcConnectionFactory;
import io.opendrs.jdbc.dialect.MysqlDialect;
import io.opendrs.jdbc.dialect.PostgresDialect;
import io.opendrs.migration.domain.ConnectionInfo;
import io.opendrs.migration.domain.JobPhase;
import io.opendrs.migration.domain.JobState;
import io.opendrs.migration.mapper.MigrationTaskMapper;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
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

    @MockitoBean
    private JdbcConnectionFactory jdbcConnectionFactory;

    @MockitoBean
    private MysqlDialect mysqlDialect;

    @MockitoBean
    private PostgresDialect postgresDialect;

    private JdbcConnection conn;

    @BeforeEach
    void stubConnections() {
        Mockito.reset(jdbcConnectionFactory, mysqlDialect, postgresDialect);
        conn = Mockito.mock(JdbcConnection.class);
        when(jdbcConnectionFactory.open(any(ConnectionInfo.class))).thenReturn(conn);
        when(conn.queryOne(anyString(), any())).thenAnswer(invocation -> {
            String sql = invocation.getArgument(0);
            if (sql.contains("log_bin")) {
                return 1;
            }
            if (sql.contains("binlog_format")) {
                return "ROW";
            }
            if (sql.contains("gtid_mode")) {
                return "ON";
            }
            return 1;
        });
    }

    @Test
    void precheckFromCreatedSucceedsAutoStarts() throws Exception {
        stubSourceOk();
        stubTargetAbsent();
        long id = createMysqlToPostgres("precheck-ok");

        mockMvc.perform(post(BASE + "/" + id + "/precheck"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1000))
                .andExpect(jsonPath("$.message").value("success"));

        JsonNode done = awaitPrecheckFinished(id);
        assertThat(done.path("ok").asBoolean()).isTrue();
        assertThat(done.path("jobPhase").asString()).isEqualTo("PRECHECKED");
        assertThat(done.path("jobState").asString()).isEqualTo("STARTING");
        assertThat(done.path("results").isArray()).isTrue();
        assertThat(done.path("sourceResults").isArray()).isTrue();
        assertThat(done.path("targetResults").isArray()).isTrue();

        mockMvc.perform(get(BASE + "/" + id + "/precheck"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1000))
                .andExpect(jsonPath("$.data.ok").value(true))
                .andExpect(jsonPath("$.data.jobPhase").value("PRECHECKED"))
                .andExpect(jsonPath("$.data.jobState").value("STARTING"))
                .andExpect(jsonPath("$.data.results[?(@.name=='log_bin' && @.ok==true)]").isNotEmpty())
                .andExpect(jsonPath("$.data.results[?(@.name=='table_absent')]").isNotEmpty())
                .andExpect(jsonPath("$.data.sourceResults[?(@.name=='log_bin')]").isNotEmpty())
                .andExpect(jsonPath("$.data.targetResults[?(@.name=='table_absent')]").isNotEmpty());

        var stored = taskMapper.findById(id);
        assertThat(stored.getJobPhase()).isEqualTo(JobPhase.PRECHECKED);
        assertThat(stored.getJobState()).isEqualTo(JobState.STARTING);
        assertThat(stored.getErrorMessage()).isNull();
        assertThat(stored.getPrecheckResultsJson()).isNotNull();
        assertThat(stored.getPrecheckResultsJson().source()).isNotEmpty();
        assertThat(stored.getPrecheckResultsJson().target()).isNotEmpty();

        mockMvc.perform(post(BASE + "/" + id + "/start"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1003));
    }

    @Test
    void postPrecheckReturnsWhileJdbcBlocked() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(jdbcConnectionFactory.open(any(ConnectionInfo.class))).thenAnswer(invocation -> {
            entered.countDown();
            if (!release.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("test latch timed out");
            }
            return conn;
        });
        when(mysqlDialect.schemaExists(any(), any())).thenReturn(true);
        when(mysqlDialect.tableExists(any(), any(), any())).thenReturn(true);
        stubTargetAbsent();
        long id = createMysqlToPostgres("precheck-async");

        try {
            long startedAt = System.nanoTime();
            mockMvc.perform(post(BASE + "/" + id + "/precheck"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(1000))
                    .andExpect(jsonPath("$.data.ok").value(false))
                    .andExpect(jsonPath("$.data.jobPhase").value("PRECHECKING"));
            assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)).isLessThan(1500);
            assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();

            mockMvc.perform(get(BASE + "/" + id + "/precheck"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.ok").value(false))
                    .andExpect(jsonPath("$.data.jobPhase").value("PRECHECKING"));

            mockMvc.perform(post(BASE + "/" + id + "/precheck"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(1000))
                    .andExpect(jsonPath("$.data.jobPhase").value("PRECHECKING"));
        } finally {
            release.countDown();
        }
        JsonNode done = awaitPrecheckFinished(id);
        assertThat(done.path("ok").asBoolean()).isTrue();
        assertThat(done.path("jobPhase").asString()).isEqualTo("PRECHECKED");
        assertThat(done.path("jobState").asString()).isEqualTo("STARTING");
    }

    @Test
    void precheckFailedCheckResultLeavesPrecheckingFailed() throws Exception {
        when(mysqlDialect.schemaExists(any(), any())).thenReturn(false);
        stubTargetAbsent();
        long id = createMysqlToPostgres("precheck-fail");

        mockMvc.perform(post(BASE + "/" + id + "/precheck"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1000));

        JsonNode done = awaitPrecheckFinished(id);
        assertThat(done.path("ok").asBoolean()).isFalse();
        assertThat(done.path("jobPhase").asString()).isEqualTo("PRECHECKING");
        assertThat(done.path("jobState").asString()).isEqualTo("FAILED");

        mockMvc.perform(get(BASE + "/" + id + "/precheck"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.ok").value(false))
                .andExpect(jsonPath("$.data.jobPhase").value("PRECHECKING"))
                .andExpect(jsonPath("$.data.jobState").value("FAILED"))
                .andExpect(jsonPath("$.data.results[?(@.ok==false)].name").isNotEmpty());

        var stored = taskMapper.findById(id);
        assertThat(stored.getJobPhase()).isEqualTo(JobPhase.PRECHECKING);
        assertThat(stored.getJobState()).isEqualTo(JobState.FAILED);
        assertThat(stored.getErrorMessage()).contains("schema_exists");

        mockMvc.perform(get(BASE + "/" + id + "/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.jobPhase").value("PRECHECKING"))
                .andExpect(jsonPath("$.data.jobState").value("FAILED"))
                .andExpect(jsonPath("$.data.error").isNotEmpty());
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
    void getPrecheckOnCreatedReturnsEmptyResults() throws Exception {
        long id = createMysqlToPostgres("precheck-get-created");
        mockMvc.perform(get(BASE + "/" + id + "/precheck"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1000))
                .andExpect(jsonPath("$.data.ok").value(false))
                .andExpect(jsonPath("$.data.jobPhase").value("CREATED"))
                .andExpect(jsonPath("$.data.results").isArray())
                .andExpect(jsonPath("$.data.results").isEmpty());
    }

    @Test
    void precheckFromStartingReturns1003() throws Exception {
        stubSourceOk();
        stubTargetAbsent();
        long id = createMysqlToPostgres("precheck-starting");
        mockMvc.perform(post(BASE + "/" + id + "/precheck")).andExpect(status().isOk());
        JsonNode done = awaitPrecheckFinished(id);
        assertThat(done.path("jobState").asString()).isEqualTo("STARTING");

        mockMvc.perform(post(BASE + "/" + id + "/precheck"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1003));
    }

    @Test
    void precheckRerunFromFailedThenFromStopped() throws Exception {
        stubSourceOk();
        stubTargetAbsent();
        long id = createMysqlToPostgres("precheck-rerun");

        when(mysqlDialect.schemaExists(any(), any())).thenReturn(false);
        mockMvc.perform(post(BASE + "/" + id + "/precheck")).andExpect(status().isOk());
        JsonNode failed = awaitPrecheckFinished(id);
        assertThat(failed.path("ok").asBoolean()).isFalse();
        assertThat(failed.path("jobState").asString()).isEqualTo("FAILED");

        stubSourceOk();
        mockMvc.perform(post(BASE + "/" + id + "/precheck"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1000));
        JsonNode recovered = awaitPrecheckFinished(id);
        assertThat(recovered.path("ok").asBoolean()).isTrue();
        assertThat(recovered.path("jobPhase").asString()).isEqualTo("PRECHECKED");
        assertThat(recovered.path("jobState").asString()).isEqualTo("STARTING");
        assertThat(taskMapper.findById(id).getErrorMessage()).isNull();

        mockMvc.perform(post(BASE + "/" + id + "/stop"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.jobState").value("STOPPED"));
        mockMvc.perform(post(BASE + "/" + id + "/precheck")).andExpect(status().isOk());
        JsonNode rerun = awaitPrecheckFinished(id);
        assertThat(rerun.path("ok").asBoolean()).isTrue();
        assertThat(rerun.path("jobState").asString()).isEqualTo("STARTING");
    }

    @Test
    void precheckFromStuckPrecheckingReturnsCurrentWithoutSecondRun() throws Exception {
        stubSourceOk();
        stubTargetAbsent();
        long id = createMysqlToPostgres("precheck-stuck");
        taskMapper.updateJobControl(id, JobPhase.PRECHECKING, null);

        mockMvc.perform(post(BASE + "/" + id + "/precheck"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1000))
                .andExpect(jsonPath("$.data.ok").value(false))
                .andExpect(jsonPath("$.data.jobPhase").value("PRECHECKING"));
        assertThat(taskMapper.findById(id).getJobPhase()).isEqualTo(JobPhase.PRECHECKING);
        assertThat(taskMapper.findById(id).getJobState()).isNull();
    }

    @Test
    void precheckOracleSourceReturns1001() throws Exception {
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
                .andExpect(jsonPath("$.message").value("No precheck registered for source type: ORACLE"));
        assertThat(taskMapper.findById(id).getJobPhase()).isEqualTo(JobPhase.CREATED);
        assertThat(taskMapper.findById(id).getJobState()).isNull();
    }

    @Test
    void connectFailureIsCheckResultNot1001() throws Exception {
        when(jdbcConnectionFactory.open(any(ConnectionInfo.class)))
                .thenThrow(io.opendrs.common.error.AppException.of(
                        io.opendrs.common.error.ErrorCode.CONNECTION_TEST_FAILED,
                        "Connection test failed: MYSQL 10.0.0.2:3306 — refused"));
        when(mysqlDialect.schemaExists(any(), any())).thenReturn(true);
        long id = createMysqlToPostgres("precheck-connect");

        mockMvc.perform(post(BASE + "/" + id + "/precheck"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1000));

        JsonNode done = awaitPrecheckFinished(id);
        assertThat(done.path("ok").asBoolean()).isFalse();
        assertThat(done.path("jobPhase").asString()).isEqualTo("PRECHECKING");
        assertThat(done.path("jobState").asString()).isEqualTo("FAILED");
        assertThat(done.path("results").get(0).path("name").asString()).isEqualTo("connect");
        assertThat(done.path("results").get(0).path("ok").asBoolean()).isFalse();
    }

    private JsonNode awaitPrecheckFinished(long id) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        ResultActions last = null;
        while (System.nanoTime() < deadline) {
            last = mockMvc.perform(get(BASE + "/" + id + "/precheck")).andExpect(status().isOk());
            MvcResult result = last.andReturn();
            JsonNode data = jsonMapper.readTree(result.getResponse().getContentAsString()).path("data");
            String phase = data.path("jobPhase").asString();
            String state = data.path("jobState").isNull() ? null : data.path("jobState").asString();
            if ("PRECHECKED".equals(phase)
                    || JobPhase.SCHEMA_SNAPSHOT.name().equals(phase)
                    || JobPhase.FULL.name().equals(phase)
                    || JobPhase.INCREMENTAL.name().equals(phase)
                    || "FAILED".equals(state)) {
                return data;
            }
            Thread.sleep(20);
        }
        String body = last == null ? "<none>" : last.andReturn().getResponse().getContentAsString();
        throw new AssertionError("precheck did not finish for task " + id + ": " + body);
    }

    private void stubSourceOk() {
        when(mysqlDialect.schemaExists(any(), any())).thenReturn(true);
        when(mysqlDialect.tableExists(any(), any(), any())).thenReturn(true);
    }

    private void stubTargetAbsent() {
        when(postgresDialect.schemaExists(any(), any())).thenReturn(false);
        when(postgresDialect.tableExists(any(), any(), any())).thenReturn(false);
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

    private static String mysqlPostgresBody(String name) {
        return """
                {
                  "name": "%s",
                  "mode": "FULL_AND_INCREMENTAL",
                  "source": {
                    "type": "MYSQL",
                    "host": "10.0.0.2",
                    "port": 3306,
                    "database": "hr",
                    "username": "cdc",
                    "password": "secret-source",
                    "extra": { "useSsl": false, "serverTimezone": "UTC" }
                  },
                  "target": {
                    "type": "POSTGRESQL",
                    "host": "10.0.0.3",
                    "port": 5432,
                    "database": "appdb",
                    "username": "drs",
                    "password": "secret-target",
                    "extra": { "sslmode": "disable" }
                  },
                  "tables": {
                    "objects": [
                      { "schema": "hr", "tables": ["emp", "dept"] }
                    ],
                    "mappings": {
                      "schema": [ { "source": "hr", "target": "hr" } ]
                    }
                  }
                }
                """.formatted(name);
    }

    private static String oracleMysqlBody(String name) {
        return """
                {
                  "name": "%s",
                  "mode": "FULL_AND_INCREMENTAL",
                  "source": {
                    "type": "ORACLE",
                    "host": "10.0.0.1",
                    "port": 1521,
                    "database": "ORCL",
                    "username": "cdc",
                    "password": "secret-source"
                  },
                  "target": {
                    "type": "MYSQL",
                    "host": "10.0.0.2",
                    "port": 3306,
                    "database": "hr",
                    "username": "drs",
                    "password": "secret-target"
                  },
                  "tables": {
                    "objects": [
                      { "schema": "HR", "tables": ["EMPLOYEES"] }
                    ]
                  }
                }
                """.formatted(name);
    }
}
