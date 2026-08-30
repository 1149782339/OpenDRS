package io.opendrs.migration.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.anyOf;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.opendrs.migration.domain.ConnectionInfo;
import io.opendrs.migration.domain.TaskState;
import io.opendrs.migration.mapper.ConnectionInfoMapper;
import io.opendrs.migration.mapper.MigrationTaskMapper;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class MigrationTaskApiTest {

    private static final String BASE = "/api/v1/migration/tasks";
    private static final Set<String> AFTER_START = Set.of(
            TaskState.SCHEMA_SNAPSHOTTING.name(),
            TaskState.FULL.name(),
            TaskState.INCREMENTAL.name(),
            TaskState.STOPPED.name());

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JsonMapper jsonMapper;

    @Autowired
    private ConnectionInfoMapper connectionInfoMapper;

    @Autowired
    private MigrationTaskMapper taskMapper;

    @Test
    void createSuccessMasksPasswordPersistsConnectionsAndUsesEnvelope() throws Exception {
        long id = createTask("hr-oracle-to-mysql", "FULL_AND_INCREMENTAL");

        mockMvc.perform(get(BASE + "/" + id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1000))
                .andExpect(jsonPath("$.message").value("success"))
                .andExpect(jsonPath("$.data.id").value(id))
                .andExpect(jsonPath("$.data.name").value("hr-oracle-to-mysql"))
                .andExpect(jsonPath("$.data.mode").value("FULL_AND_INCREMENTAL"))
                .andExpect(jsonPath("$.data.state").value("CREATED"))
                .andExpect(jsonPath("$.data.source.password").value("***"))
                .andExpect(jsonPath("$.data.target.password").value("***"))
                .andExpect(jsonPath("$.data.source.host").value("10.0.0.1"))
                .andExpect(jsonPath("$.data.source.extra.pdb").value("ORCLPDB1"))
                .andExpect(jsonPath("$.data.source.extra.connectionType").value("SERVICE"))
                .andExpect(jsonPath("$.data.target.extra.useSsl").value(false))
                .andExpect(jsonPath("$.data.target.extra.serverTimezone").value("UTC"));

        var task = taskMapper.findById(id);
        assertThat(task.getSourceConnectionId()).isNotNull();
        assertThat(task.getTargetConnectionId()).isNotNull();
        ConnectionInfo source = connectionInfoMapper.findById(task.getSourceConnectionId());
        ConnectionInfo target = connectionInfoMapper.findById(task.getTargetConnectionId());
        assertThat(source.getName()).isEqualTo("hr-oracle-to-mysql-source");
        assertThat(target.getName()).isEqualTo("hr-oracle-to-mysql-target");
        assertThat(source.getPassword()).isEqualTo("secret-source");
        assertThat(source.getExtra()).containsEntry("pdb", "ORCLPDB1");
        assertThat(source.getExtra()).containsEntry("connectionType", "SERVICE");
        assertThat(target.getDbName()).isEqualTo("hr");
        assertThat(target.getExtra()).containsEntry("useSsl", false);
        assertThat(target.getExtra()).containsEntry("serverTimezone", "UTC");
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
    void deleteWhileRunningReturns1003() throws Exception {
        long id = createTask("running-delete", "FULL_AND_INCREMENTAL");
        markPrechecked(id);
        mockMvc.perform(post(BASE + "/" + id + "/start"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1000));
        awaitState(id, AFTER_START);

        mockMvc.perform(delete(BASE + "/" + id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1003));
    }

    @Test
    void startFromCreatedReturns1003ThenPrecheckedLaunchesJob() throws Exception {
        long id = createTask("start-created", "FULL_AND_INCREMENTAL");

        mockMvc.perform(post(BASE + "/" + id + "/start"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1003))
                .andExpect(jsonPath("$.message").value("Task " + id + " cannot be started from state CREATED"));

        markPrechecked(id);

        mockMvc.perform(post(BASE + "/" + id + "/start"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1000))
                .andExpect(jsonPath("$.message").value("success"))
                .andExpect(jsonPath("$.data.id").value(id));

        String state = awaitState(id, AFTER_START);
        assertThat(state).isIn(
                TaskState.SCHEMA_SNAPSHOTTING.name(),
                TaskState.FULL.name(),
                TaskState.INCREMENTAL.name());
        mockMvc.perform(get(BASE + "/" + id + "/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1000))
                .andExpect(jsonPath("$.data.state").value(anyOf(
                        is(TaskState.SCHEMA_SNAPSHOTTING.name()),
                        is(TaskState.FULL.name()),
                        is(TaskState.INCREMENTAL.name()))))
                .andExpect(jsonPath("$.data.progress.tablesTotal").value(0))
                .andExpect(jsonPath("$.data.progress.tablesDone").value(0))
                .andExpect(jsonPath("$.data.progress.rowsDone").value(0))
                .andExpect(jsonPath("$.data.offset.scn").value(nullValue()))
                .andExpect(jsonPath("$.data.offset.gtid").value(nullValue()))
                .andExpect(jsonPath("$.data.error").value(nullValue()));
    }

    @Test
    void listStatusStopStubAndFullOnlyDeleteFollowEnvelope() throws Exception {
        long incrementalId = createTask("lifecycle-inc", "FULL_AND_INCREMENTAL");

        mockMvc.perform(get(BASE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1000))
                .andExpect(jsonPath("$.data[?(@.id==" + incrementalId + ")].source.type").value("ORACLE"))
                .andExpect(jsonPath("$.data[?(@.id==" + incrementalId + ")].target.type").value("MYSQL"));

        mockMvc.perform(get(BASE + "/" + incrementalId + "/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1000))
                .andExpect(jsonPath("$.data.state").value("CREATED"));

        mockMvc.perform(post(BASE + "/" + incrementalId + "/stop"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1003));

        markPrechecked(incrementalId);
        mockMvc.perform(post(BASE + "/" + incrementalId + "/start"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1000));
        awaitState(incrementalId, AFTER_START);

        mockMvc.perform(post(BASE + "/" + incrementalId + "/stop"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1003));

        long fullOnlyId = createTask("lifecycle-full", "FULL_ONLY");
        markPrechecked(fullOnlyId);
        mockMvc.perform(post(BASE + "/" + fullOnlyId + "/start"))
                .andExpect(status().isOk());
        awaitState(fullOnlyId, Set.of(TaskState.STOPPED.name()));

        mockMvc.perform(delete(BASE + "/" + fullOnlyId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1000))
                .andExpect(jsonPath("$.message").value("success"))
                .andExpect(jsonPath("$.data").value(nullValue()));

        mockMvc.perform(get(BASE + "/" + fullOnlyId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1002));
    }

    @Test
    void startWhileAlreadyRunningReturns1003() throws Exception {
        long id = createTask("double-start", "FULL_AND_INCREMENTAL");
        markPrechecked(id);
        mockMvc.perform(post(BASE + "/" + id + "/start"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1000));
        awaitState(id, AFTER_START);

        mockMvc.perform(post(BASE + "/" + id + "/start"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1003));
    }

    @Test
    void startFromFailedReturns1003() throws Exception {
        long id = createTask("start-failed", "FULL_AND_INCREMENTAL");
        taskMapper.markFailed(id, "previous failure");

        mockMvc.perform(post(BASE + "/" + id + "/start"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1003))
                .andExpect(jsonPath("$.message").value("Task " + id + " cannot be started from state FAILED"));
    }

    @Test
    void deleteWhilePrecheckingReturns1003() throws Exception {
        long id = createTask("prechecking-delete", "FULL_AND_INCREMENTAL");
        taskMapper.updateState(id, TaskState.PRECHECKING);

        mockMvc.perform(delete(BASE + "/" + id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1003));
        assertThat(taskMapper.findById(id)).isNotNull();
    }

    @Test
    void deleteWhilePrecheckedIsAllowed() throws Exception {
        long id = createTask("prechecked-delete", "FULL_AND_INCREMENTAL");
        taskMapper.updateState(id, TaskState.PRECHECKED);

        mockMvc.perform(delete(BASE + "/" + id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1000));
        mockMvc.perform(get(BASE + "/" + id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1002));
    }

    private void markPrechecked(long id) {
        taskMapper.updateState(id, TaskState.PRECHECKED);
    }

    private String awaitState(long id, Set<String> accepted) throws Exception {
        for (int i = 0; i < 80; i++) {
            MvcResult result = mockMvc.perform(get(BASE + "/" + id + "/status"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(anyOf(is(1000), is(1002))))
                    .andReturn();
            JsonNode root = jsonMapper.readTree(result.getResponse().getContentAsString());
            String state = root.path("data").path("state").asString();
            if (accepted.contains(state)) {
                return state;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("Task " + id + " did not reach " + accepted);
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
