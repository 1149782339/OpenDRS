package io.opendrs.migration.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.opendrs.migration.domain.ConnectionInfo;
import io.opendrs.migration.domain.JobPhase;
import io.opendrs.migration.domain.JobState;
import io.opendrs.migration.mapper.ConnectionInfoMapper;
import io.opendrs.migration.mapper.MigrationTaskMapper;
import org.junit.jupiter.api.Test;
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
                .andExpect(jsonPath("$.data.jobPhase").value("CREATED"))
                .andExpect(jsonPath("$.data.jobState").value(nullValue()))
                .andExpect(jsonPath("$.data.source.password").value("***"))
                .andExpect(jsonPath("$.data.target.password").value("***"))
                .andExpect(jsonPath("$.data.source.host").value("10.0.0.1"))
                .andExpect(jsonPath("$.data.source.extra.pdb").value("ORCLPDB1"));

        var task = taskMapper.findById(id);
        assertThat(task.getJobPhase()).isEqualTo(JobPhase.CREATED);
        assertThat(task.getJobState()).isNull();
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
                  "source": %s,
                  "target": %s,
                  "tables": {
                    "objects": [
                      { "schema": "HR", "tables": ["EMPLOYEES"] },
                      { "schema": "SCOTT", "tables": ["EMP"] }
                    ],
                    "mappings": {
                      "schema": [
                        { "source": "HR", "target": "hr" },
                        { "source": "SCOTT", "target": "hr" }
                      ]
                    }
                  }
                }
                """.formatted(sourceJson(), targetJson());

        mockMvc.perform(post(BASE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1001))
                .andExpect(jsonPath("$.message").value("Two schemas map to the same target schema: hr"));
    }

    @Test
    void getMissingTaskReturns1002() throws Exception {
        mockMvc.perform(get(BASE + "/999999"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1002))
                .andExpect(jsonPath("$.message").value("Task not found: 999999"));
    }

    @Test
    void startFromCreatedNullIsRejected() throws Exception {
        long id = createTask("start-created", "FULL_AND_INCREMENTAL");

        mockMvc.perform(post(BASE + "/" + id + "/start"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1003))
                .andExpect(jsonPath("$.message").value("Task " + id + " cannot be started from jobState null"));
    }

    @Test
    void stopWhileStartingGoesStoppedThenStartFromStopped() throws Exception {
        long id = createTask("stop-starting", "FULL_AND_INCREMENTAL");
        taskMapper.updateJobControl(id, JobPhase.PRECHECKED, JobState.STARTING);

        mockMvc.perform(post(BASE + "/" + id + "/stop"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1000))
                .andExpect(jsonPath("$.data.jobPhase").value("PRECHECKED"))
                .andExpect(jsonPath("$.data.jobState").value("STOPPED"));

        mockMvc.perform(post(BASE + "/" + id + "/start"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1000))
                .andExpect(jsonPath("$.data.jobPhase").value("PRECHECKED"))
                .andExpect(jsonPath("$.data.jobState").value("STARTING"));
    }

    @Test
    void stopWhileNotRunningRejected() throws Exception {
        long id = createTask("stop-null", "FULL_AND_INCREMENTAL");
        mockMvc.perform(post(BASE + "/" + id + "/stop"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1003));

        taskMapper.updateJobControl(id, JobPhase.PRECHECKING, JobState.FAILED);
        mockMvc.perform(post(BASE + "/" + id + "/stop"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1003));
    }

    @Test
    void startWhileStartingOrRunningRejected() throws Exception {
        long id = createTask("double-start", "FULL_AND_INCREMENTAL");
        taskMapper.updateJobControl(id, JobPhase.PRECHECKED, JobState.STARTING);
        mockMvc.perform(post(BASE + "/" + id + "/start"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1003));

        taskMapper.updateJobControl(id, JobPhase.FULL, JobState.RUNNING);
        mockMvc.perform(post(BASE + "/" + id + "/start"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1003));
    }

    @Test
    void stopWhileStoppingAndStoppedIsIdempotent() throws Exception {
        long id = createTask("stop-idempotent", "FULL_AND_INCREMENTAL");
        taskMapper.updateJobControl(id, JobPhase.INCREMENTAL, JobState.STOPPING);
        mockMvc.perform(post(BASE + "/" + id + "/stop"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1000))
                .andExpect(jsonPath("$.data.jobState").value("STOPPING"));

        taskMapper.updateJobControl(id, JobPhase.INCREMENTAL, JobState.STOPPED);
        mockMvc.perform(post(BASE + "/" + id + "/stop"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1000))
                .andExpect(jsonPath("$.data.jobState").value("STOPPED"));
    }

    @Test
    void startFromFailedAfterRunIsAllowedButNotFromPrecheckFailed() throws Exception {
        long id = createTask("start-failed", "FULL_AND_INCREMENTAL");
        taskMapper.updateJobControl(id, JobPhase.FULL, JobState.FAILED);
        mockMvc.perform(post(BASE + "/" + id + "/start"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1000))
                .andExpect(jsonPath("$.data.jobPhase").value("FULL"))
                .andExpect(jsonPath("$.data.jobState").value("STARTING"));

        long precheckFailed = createTask("start-precheck-failed", "FULL_AND_INCREMENTAL");
        taskMapper.updateJobControl(precheckFailed, JobPhase.PRECHECKING, JobState.FAILED);
        mockMvc.perform(post(BASE + "/" + precheckFailed + "/start"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1003));
    }

    @Test
    void deleteWhileInFlightReturns1003() throws Exception {
        long starting = createTask("delete-starting", "FULL_AND_INCREMENTAL");
        taskMapper.updateJobControl(starting, JobPhase.PRECHECKED, JobState.STARTING);
        mockMvc.perform(delete(BASE + "/" + starting))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1003));

        long running = createTask("delete-running", "FULL_AND_INCREMENTAL");
        taskMapper.updateJobControl(running, JobPhase.FULL, JobState.RUNNING);
        mockMvc.perform(delete(BASE + "/" + running))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1003));

        long stopping = createTask("delete-stopping", "FULL_AND_INCREMENTAL");
        taskMapper.updateJobControl(stopping, JobPhase.INCREMENTAL, JobState.STOPPING);
        mockMvc.perform(delete(BASE + "/" + stopping))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1003));
    }

    @Test
    void deleteWhilePrecheckingReturns1003AndStoppedCanDelete() throws Exception {
        long prechecking = createTask("prechecking-delete", "FULL_AND_INCREMENTAL");
        taskMapper.updateJobControl(prechecking, JobPhase.PRECHECKING, null);
        mockMvc.perform(delete(BASE + "/" + prechecking))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1003));
        assertThat(taskMapper.findById(prechecking)).isNotNull();

        long stopped = createTask("stopped-delete", "FULL_ONLY");
        taskMapper.updateJobControl(stopped, JobPhase.PRECHECKED, JobState.STOPPED);
        mockMvc.perform(delete(BASE + "/" + stopped))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1000))
                .andExpect(jsonPath("$.data").value(nullValue()));
        mockMvc.perform(get(BASE + "/" + stopped))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1002));
    }

    @Test
    void listAndStatusUseJobPhaseAndJobState() throws Exception {
        long id = createTask("lifecycle-list", "FULL_AND_INCREMENTAL");

        mockMvc.perform(get(BASE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1000))
                .andExpect(jsonPath("$.data[?(@.id==" + id + ")].jobPhase").value("CREATED"))
                .andExpect(jsonPath("$.data[?(@.id==" + id + ")].source.type").value("ORACLE"));

        mockMvc.perform(get(BASE + "/" + id + "/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1000))
                .andExpect(jsonPath("$.data.jobPhase").value("CREATED"))
                .andExpect(jsonPath("$.data.jobState").value(nullValue()))
                .andExpect(jsonPath("$.data.progress.tablesTotal").value(0))
                .andExpect(jsonPath("$.data.offset.scn").value(nullValue()));
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

    private static String validCreateBody(String name, String mode) {
        return """
                {
                  "name": "%s",
                  "mode": "%s",
                  "source": %s,
                  "target": %s,
                  "tables": {
                    "objects": [
                      { "schema": "HR", "tables": ["EMPLOYEES", "DEPARTMENTS"] },
                      { "schema": "SCOTT", "tables": ["EMP"] }
                    ],
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
                  "options": {
                    "fullDumpParallelism": 8,
                    "batchSize": 1000
                  }
                }
                """.formatted(name, mode, sourceJson(), targetJson());
    }

    private static String sourceJson() {
        return """
                {
                  "type": "ORACLE",
                  "host": "10.0.0.1",
                  "port": 1521,
                  "database": "ORCL",
                  "username": "cdc",
                  "password": "secret-source",
                  "extra": {
                    "pdb": "ORCLPDB1",
                    "connectionType": "SERVICE"
                  }
                }
                """;
    }

    private static String targetJson() {
        return """
                {
                  "type": "MYSQL",
                  "host": "10.0.0.2",
                  "port": 3306,
                  "database": "hr",
                  "username": "drs",
                  "password": "secret-target",
                  "extra": {
                    "useSsl": false,
                    "serverTimezone": "UTC"
                  }
                }
                """;
    }
}
