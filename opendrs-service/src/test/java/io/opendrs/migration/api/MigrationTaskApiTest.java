package io.opendrs.migration.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.opendrs.migration.domain.ConnectionInfo;
import io.opendrs.migration.domain.DbType;
import io.opendrs.migration.domain.MigrationPhase;
import io.opendrs.engine.core.TaskState;
import io.opendrs.migration.mapper.ConnectionInfoMapper;
import io.opendrs.migration.mapper.MigrationTaskMapper;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.i18n.LocaleContextHolder;
import java.util.Locale;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class MigrationTaskApiTest {

    private static final String BASE = "/api/v1/migration/tasks";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JsonMapper jsonMapper;

    @Autowired
    private ConnectionInfoMapper connectionInfoMapper;

    @Autowired
    private MigrationTaskMapper taskMapper;

    /**
     * Tasks reference registered data sources instead of carrying connection copies, so every
     * create request needs two rows to point at. Both are inserted once and looked up by name
     * afterwards, since the H2 schema is shared for the whole context.
     */
    private long sourceId;

    private long targetId;

    @org.junit.jupiter.api.AfterEach
    void resetLocale() {
        LocaleContextHolder.resetLocaleContext();
    }

    @BeforeEach
    void ensureDataSources() {
        // MockMvc (Spring 7 TestDispatcherServlet) ignores the LocaleResolver, so message
        // assertions pin the thread locale directly; the header alone is not enough here.
        LocaleContextHolder.setLocale(Locale.ENGLISH);
        sourceId = dataSource(
                "hr-oracle-to-mysql-source",
                DbType.ORACLE,
                "10.0.0.1",
                1521,
                "ORCL",
                "cdc",
                "secret-source",
                Map.of("pdb", "ORCLPDB1", "connectionType", "SERVICE"));
        targetId = dataSource(
                "hr-oracle-to-mysql-target",
                DbType.MYSQL,
                "10.0.0.2",
                3306,
                "hr",
                "drs",
                "secret-target",
                Map.of("useSsl", false, "serverTimezone", "UTC"));
    }

    private long dataSource(
            String name,
            DbType type,
            String host,
            int port,
            String database,
            String username,
            String password,
            Map<String, Object> extra) {
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
        connection.setExtra(extra);
        connectionInfoMapper.insert(connection);
        return connection.getId();
    }

    @Test
    void createSuccessMasksPasswordAndSetsCreatedNullJobState() throws Exception {
        long id = createTask("hr-oracle-to-mysql", "FULL_AND_INCREMENTAL");

        mockMvc.perform(get(BASE + "/" + id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1000))
                .andExpect(jsonPath("$.message").value("success"))
                .andExpect(jsonPath("$.data.id").value(id))
                .andExpect(jsonPath("$.data.name").value("hr-oracle-to-mysql"))
                .andExpect(jsonPath("$.data.mode").value("FULL_AND_INCREMENTAL"))
                .andExpect(jsonPath("$.data.phase").value("CREATED"))
                .andExpect(jsonPath("$.data.state").value(nullValue()))
                .andExpect(jsonPath("$.data.source.password").value("***"))
                .andExpect(jsonPath("$.data.target.password").value("***"))
                .andExpect(jsonPath("$.data.source.host").value("10.0.0.1"))
                .andExpect(jsonPath("$.data.source.extra.pdb").value("ORCLPDB1"));

        var task = taskMapper.findById(id);
        assertThat(task.getPhase()).isEqualTo(MigrationPhase.CREATED);
        assertThat(task.getState()).isNull();
        // The task points at the registered data sources; it does not own a copy of them.
        assertThat(task.getSourceConnectionId()).isEqualTo(sourceId);
        assertThat(task.getTargetConnectionId()).isEqualTo(targetId);
        ConnectionInfo source = connectionInfoMapper.findById(task.getSourceConnectionId());
        ConnectionInfo target = connectionInfoMapper.findById(task.getTargetConnectionId());
        assertThat(source.getName()).isEqualTo("hr-oracle-to-mysql-source");
        assertThat(target.getName()).isEqualTo("hr-oracle-to-mysql-target");
        assertThat(source.getPassword()).isEqualTo("secret-source");
    }

    @Test
    void createMappingConflictReturns1001() throws Exception {
        String body = """
                {
                  "name": "conflict",
                  "mode": "FULL_ONLY",
                  "sourceConnectionId": %d,
                  "targetConnectionId": %d,
                  "options": {
                    "selection": {
                      "mode": "TABLE",
                      "schemas": [],
                      "tables": [
                        { "schema": "HR", "table": "EMPLOYEES" },
                        { "schema": "SCOTT", "table": "EMP" }
                      ],
                      "excludeSchemas": [],
                      "excludeTables": [],
                      "mappings": {
                        "schema": [
                          { "source": "HR", "target": "hr" },
                          { "source": "SCOTT", "target": "hr" }
                        ]
                      }
                    }
                  }
                }
                """.formatted(sourceId, targetId);

        mockMvc.perform(post(BASE)
                        .header("Accept-Language", "en")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1001))
                .andExpect(jsonPath("$.message")
                        .value("Invalid table mapping: Two schemas map to the same target schema: hr"));
    }

    @Test
    void createWithPartialOptionsFillsDefaults() throws Exception {
        String body = """
                {
                  "name": "partial-options",
                  "mode": "FULL_AND_INCREMENTAL",
                  "sourceConnectionId": %d,
                  "targetConnectionId": %d,
                  "options": {
                    "topology": "ONE_WAY",
                    "selection": { "mode": "ALL" },
                    "full": { "exportConcurrency": 4 },
                    "incremental": { "replayConcurrency": 2 }
                  }
                }
                """.formatted(sourceId, targetId);

        mockMvc.perform(post(BASE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1000))
                .andExpect(jsonPath("$.data.options.full.exportConcurrency").value(4))
                .andExpect(jsonPath("$.data.options.full.importConcurrency").value(8))
                .andExpect(jsonPath("$.data.options.full.importMode").value("COPY"))
                .andExpect(jsonPath("$.data.options.incremental.replayConcurrency").value(2))
                .andExpect(jsonPath("$.data.options.incremental.conflictPolicy").value("OVERWRITE"));
    }

    @Test
    void createWithUnknownDataSourceReturns1004() throws Exception {
        String body = """
                {
                  "name": "unknown-source",
                  "mode": "FULL_ONLY",
                  "sourceConnectionId": 999999,
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
                """.formatted(targetId);

        mockMvc.perform(post(BASE)
                        .header("Accept-Language", "en")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1004))
                .andExpect(jsonPath("$.message").value("Source data source not found: 999999"));
    }

    @Test
    void getMissingTaskReturns1002() throws Exception {
        mockMvc.perform(get(BASE + "/999999")
                        .header("Accept-Language", "en"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1002))
                .andExpect(jsonPath("$.message").value("Task not found: 999999"));
    }

    @Test
    void startFromCreatedNullIsRejected() throws Exception {
        long id = createTask("start-created", "FULL_AND_INCREMENTAL");

        mockMvc.perform(post(BASE + "/" + id + "/start")
                        .header("Accept-Language", "en"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1003))
                .andExpect(jsonPath("$.message").value("Task " + id + " cannot be started from state null"));
    }

    @Test
    void startFromPrecheckedIdleIsAllowed() throws Exception {
        long id = createTask("start-prechecked-idle", "FULL_AND_INCREMENTAL");
        taskMapper.updatePhaseState(id, MigrationPhase.PRECHECKED, null);

        mockMvc.perform(post(BASE + "/" + id + "/start"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1000))
                .andExpect(jsonPath("$.data.phase").value("SCHEMA_MIGRATION"))
                .andExpect(jsonPath("$.data.state").value("STARTING"));
    }

    @Test
    void stopWhileStartingGoesStoppedThenStartFromStopped() throws Exception {
        long id = createTask("stop-starting", "FULL_AND_INCREMENTAL");
        taskMapper.updatePhaseState(id, MigrationPhase.PRECHECKED, TaskState.STARTING);

        mockMvc.perform(post(BASE + "/" + id + "/stop"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1000))
                .andExpect(jsonPath("$.data.phase").value("PRECHECKED"))
                .andExpect(jsonPath("$.data.state").value("STOPPED"));

        mockMvc.perform(post(BASE + "/" + id + "/start"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1000))
                // Confirming the migration moves the pipeline into its structure step immediately,
                // so the UI does not sit on PRECHECKED while the dispatcher is still picking it up.
                .andExpect(jsonPath("$.data.phase").value("SCHEMA_MIGRATION"))
                .andExpect(jsonPath("$.data.state").value("STARTING"));
    }

    @Test
    void stopWhileNotRunningRejected() throws Exception {
        long id = createTask("stop-null", "FULL_AND_INCREMENTAL");
        mockMvc.perform(post(BASE + "/" + id + "/stop"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1003));

        taskMapper.updatePhaseState(id, MigrationPhase.PRECHECKING, TaskState.FAILED);
        mockMvc.perform(post(BASE + "/" + id + "/stop"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1003));
    }

    @Test
    void startWhileStartingOrRunningRejected() throws Exception {
        long id = createTask("double-start", "FULL_AND_INCREMENTAL");
        taskMapper.updatePhaseState(id, MigrationPhase.PRECHECKED, TaskState.STARTING);
        mockMvc.perform(post(BASE + "/" + id + "/start"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1003));

        taskMapper.updatePhaseState(id, MigrationPhase.FULL, TaskState.RUNNING);
        mockMvc.perform(post(BASE + "/" + id + "/start"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1003));
    }

    @Test
    void stopWhileStoppingAndStoppedIsIdempotent() throws Exception {
        long id = createTask("stop-idempotent", "FULL_AND_INCREMENTAL");
        taskMapper.updatePhaseState(id, MigrationPhase.INCREMENTAL, TaskState.STOPPING);
        mockMvc.perform(post(BASE + "/" + id + "/stop"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1000))
                .andExpect(jsonPath("$.data.state").value("STOPPING"));

        taskMapper.updatePhaseState(id, MigrationPhase.INCREMENTAL, TaskState.STOPPED);
        mockMvc.perform(post(BASE + "/" + id + "/stop"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1000))
                .andExpect(jsonPath("$.data.state").value("STOPPED"));
    }

    @Test
    void startFromFailedAfterRunIsAllowedButNotFromPrecheckFailed() throws Exception {
        long id = createTask("start-failed", "FULL_AND_INCREMENTAL");
        taskMapper.updatePhaseState(id, MigrationPhase.FULL, TaskState.FAILED);
        mockMvc.perform(post(BASE + "/" + id + "/start"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1000))
                .andExpect(jsonPath("$.data.phase").value("FULL"))
                .andExpect(jsonPath("$.data.state").value("STARTING"));

        long precheckFailed = createTask("start-precheck-failed", "FULL_AND_INCREMENTAL");
        taskMapper.updatePhaseState(precheckFailed, MigrationPhase.PRECHECKING, TaskState.FAILED);
        mockMvc.perform(post(BASE + "/" + precheckFailed + "/start"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1003));
    }

    @Test
    void deleteWhileInFlightReturns1003() throws Exception {
        long starting = createTask("delete-starting", "FULL_AND_INCREMENTAL");
        taskMapper.updatePhaseState(starting, MigrationPhase.PRECHECKED, TaskState.STARTING);
        mockMvc.perform(delete(BASE + "/" + starting))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1003));

        long running = createTask("delete-running", "FULL_AND_INCREMENTAL");
        taskMapper.updatePhaseState(running, MigrationPhase.FULL, TaskState.RUNNING);
        mockMvc.perform(delete(BASE + "/" + running))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1003));

        long stopping = createTask("delete-stopping", "FULL_AND_INCREMENTAL");
        taskMapper.updatePhaseState(stopping, MigrationPhase.INCREMENTAL, TaskState.STOPPING);
        mockMvc.perform(delete(BASE + "/" + stopping))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1003));
    }

    @Test
    void deleteRequiresTerminatedAndStoppedCannotDelete() throws Exception {
        long prechecking = createTask("prechecking-delete", "FULL_AND_INCREMENTAL");
        taskMapper.updatePhaseState(prechecking, MigrationPhase.PRECHECKING, null);
        mockMvc.perform(delete(BASE + "/" + prechecking))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1003));
        assertThat(taskMapper.findById(prechecking)).isNotNull();

        // 暂停(STOPPED)不是终态：必须「结束」落到 TERMINATED 后才能删除。
        long stopped = createTask("stopped-delete", "FULL_ONLY");
        taskMapper.updatePhaseState(stopped, MigrationPhase.PRECHECKED, TaskState.STOPPED);
        mockMvc.perform(delete(BASE + "/" + stopped))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1003));
        assertThat(taskMapper.findById(stopped)).isNotNull();

        mockMvc.perform(post(BASE + "/" + stopped + "/terminate"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1000))
                .andExpect(jsonPath("$.data.state").value("TERMINATED"));
        mockMvc.perform(delete(BASE + "/" + stopped))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1000))
                .andExpect(jsonPath("$.data").value(nullValue()));
        mockMvc.perform(get(BASE + "/" + stopped))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1002));
    }

    @Test
    void terminateWhileRunningMarksTerminating() throws Exception {
        long id = createTask("terminate-running", "FULL_AND_INCREMENTAL");
        taskMapper.updatePhaseState(id, MigrationPhase.INCREMENTAL, TaskState.RUNNING);
        mockMvc.perform(post(BASE + "/" + id + "/terminate"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1000))
                .andExpect(jsonPath("$.data.state").value("TERMINATING"));

        // TERMINATED 是终态：start 拒绝，重复 terminate 幂等。
        taskMapper.updatePhaseState(id, MigrationPhase.INCREMENTAL, TaskState.TERMINATED);
        mockMvc.perform(post(BASE + "/" + id + "/start"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1003));
        mockMvc.perform(post(BASE + "/" + id + "/terminate"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1000))
                .andExpect(jsonPath("$.data.state").value("TERMINATED"));
    }

    @Test
    void terminateWhilePausedAndNeverStartedLandsImmediately() throws Exception {
        long neverStarted = createTask("terminate-null", "FULL_AND_INCREMENTAL");
        mockMvc.perform(post(BASE + "/" + neverStarted + "/terminate"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1000))
                .andExpect(jsonPath("$.data.state").value("TERMINATED"));

        long paused = createTask("terminate-paused", "FULL_AND_INCREMENTAL");
        taskMapper.updatePhaseState(paused, MigrationPhase.INCREMENTAL, TaskState.STOPPED);
        mockMvc.perform(post(BASE + "/" + paused + "/terminate"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.state").value("TERMINATED"));

        // 暂停中(STOPPING)点结束：升级为 TERMINATING，等线程收尾后落 TERMINATED。
        long pausing = createTask("terminate-pausing", "FULL_AND_INCREMENTAL");
        taskMapper.updatePhaseState(pausing, MigrationPhase.INCREMENTAL, TaskState.RUNNING);
        taskMapper.markPausing(pausing);
        mockMvc.perform(post(BASE + "/" + pausing + "/terminate"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.state").value("TERMINATING"));
    }

    @Test
    void listAndStatusUseMigrationPhaseAndJobState() throws Exception {
        long id = createTask("lifecycle-list", "FULL_AND_INCREMENTAL");

        mockMvc.perform(get(BASE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1000))
                .andExpect(jsonPath("$.data[?(@.id==" + id + ")].phase").value("CREATED"))
                .andExpect(jsonPath("$.data[?(@.id==" + id + ")].source.type").value("ORACLE"));

        mockMvc.perform(get(BASE + "/" + id + "/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1000))
                .andExpect(jsonPath("$.data.phase").value("CREATED"))
                .andExpect(jsonPath("$.data.state").value(nullValue()))
                .andExpect(jsonPath("$.data.offset.captured").value(nullValue()));
    }

    private long createTask(String name, String mode) throws Exception {
        MvcResult result = mockMvc.perform(post(BASE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validCreateBody(name, mode)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1000))
                .andReturn();
        JsonNode root = jsonMapper.readTree(result.getResponse().getContentAsString());
        return root.path("data").path("id").asLong();
    }

    private String validCreateBody(String name, String mode) {
        return """
                {
                  "name": "%s",
                  "mode": "%s",
                  "sourceConnectionId": %d,
                  "targetConnectionId": %d,
                  "options": {
                    "topology": "ONE_WAY",
                    "selection": {
                      "mode": "TABLE",
                      "schemas": [],
                      "tables": [
                        { "schema": "HR", "table": "EMPLOYEES" },
                        { "schema": "HR", "table": "DEPARTMENTS" },
                        { "schema": "SCOTT", "table": "EMP" }
                      ],
                      "excludeSchemas": [],
                      "excludeTables": [],
                      "mappings": {
                        "schema": [
                          { "source": "SCOTT", "target": "scott" }
                        ],
                        "tables": [
                          {
                            "sourceSchema": "HR",
                            "sourceTable": "EMPLOYEES",
                            "targetSchema": "hr",
                            "targetTable": "emp"
                          }
                        ]
                      }
                    },
                    "full": {
                      "objectTypes": ["SCHEMA", "DATA", "INDEX"],
                      "exportConcurrency": 8,
                      "importConcurrency": 8,
                      "importMode": "COPY",
                      "chunkMultiplier": 4,
                      "charsetAdapt": "AUTO"
                    },
                    "incremental": {
                      "replayConcurrency": 64,
                      "conflictPolicy": "OVERWRITE"
                    }
                  }
                }
                """.formatted(name, mode, sourceId, targetId);
    }
}
