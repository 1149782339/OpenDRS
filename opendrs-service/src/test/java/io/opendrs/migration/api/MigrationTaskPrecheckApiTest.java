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
                .andExpect(jsonPath("$.message").value("success"))
                .andExpect(jsonPath("$.data.ok").value(true))
                .andExpect(jsonPath("$.data.jobPhase").value("PRECHECKED"))
                .andExpect(jsonPath("$.data.jobState").value("STARTING"))
                .andExpect(jsonPath("$.data.results").isArray())
                .andExpect(jsonPath("$.data.results[?(@.name=='log_bin' && @.ok==true)]").isNotEmpty())
                .andExpect(jsonPath("$.data.results[?(@.name=='table_absent')]").isNotEmpty());

        var stored = taskMapper.findById(id);
        assertThat(stored.getJobPhase()).isEqualTo(JobPhase.PRECHECKED);
        assertThat(stored.getJobState()).isEqualTo(JobState.STARTING);
        assertThat(stored.getErrorMessage()).isNull();

        mockMvc.perform(post(BASE + "/" + id + "/start"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1003));
    }

    @Test
    void precheckFailedCheckResultLeavesPrecheckingFailed() throws Exception {
        when(mysqlDialect.schemaExists(any(), any())).thenReturn(false);
        stubTargetAbsent();
        long id = createMysqlToPostgres("precheck-fail");

        mockMvc.perform(post(BASE + "/" + id + "/precheck"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1000))
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
    }

    @Test
    void precheckFromStartingReturns1003() throws Exception {
        stubSourceOk();
        stubTargetAbsent();
        long id = createMysqlToPostgres("precheck-starting");
        mockMvc.perform(post(BASE + "/" + id + "/precheck"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1000))
                .andExpect(jsonPath("$.data.jobState").value("STARTING"));

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
        mockMvc.perform(post(BASE + "/" + id + "/precheck"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.ok").value(false))
                .andExpect(jsonPath("$.data.jobState").value("FAILED"));

        stubSourceOk();
        mockMvc.perform(post(BASE + "/" + id + "/precheck"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1000))
                .andExpect(jsonPath("$.data.ok").value(true))
                .andExpect(jsonPath("$.data.jobPhase").value("PRECHECKED"))
                .andExpect(jsonPath("$.data.jobState").value("STARTING"));
        assertThat(taskMapper.findById(id).getErrorMessage()).isNull();

        mockMvc.perform(post(BASE + "/" + id + "/stop"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.jobState").value("STOPPED"));
        mockMvc.perform(post(BASE + "/" + id + "/precheck"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.ok").value(true))
                .andExpect(jsonPath("$.data.jobState").value("STARTING"));
    }

    @Test
    void precheckFromStuckPrecheckingRetries() throws Exception {
        stubSourceOk();
        stubTargetAbsent();
        long id = createMysqlToPostgres("precheck-stuck");
        taskMapper.updateJobControl(id, JobPhase.PRECHECKING, null);

        mockMvc.perform(post(BASE + "/" + id + "/precheck"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1000))
                .andExpect(jsonPath("$.data.ok").value(true))
                .andExpect(jsonPath("$.data.jobPhase").value("PRECHECKED"))
                .andExpect(jsonPath("$.data.jobState").value("STARTING"));
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
                .andExpect(jsonPath("$.code").value(1000))
                .andExpect(jsonPath("$.data.ok").value(false))
                .andExpect(jsonPath("$.data.jobPhase").value("PRECHECKING"))
                .andExpect(jsonPath("$.data.jobState").value("FAILED"))
                .andExpect(jsonPath("$.data.results[0].name").value("connect"))
                .andExpect(jsonPath("$.data.results[0].ok").value(false));
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
