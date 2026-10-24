package io.opendrs.migration.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.opendrs.common.progress.MigrationLagListener;
import io.opendrs.engine.core.TaskState;
import io.opendrs.migration.api.request.MigrationOptions;
import io.opendrs.migration.domain.ConnectionInfo;
import io.opendrs.migration.domain.DbType;
import io.opendrs.migration.domain.MigrationPhase;
import io.opendrs.migration.domain.MigrationMode;
import io.opendrs.migration.domain.MigrationTask;
import io.opendrs.migration.mapper.ConnectionInfoMapper;
import io.opendrs.migration.mapper.MigrationTaskMapper;
import io.opendrs.migration.service.MigrationLagRegistry;
import io.opendrs.migration.service.MigrationTaskService;
import java.util.List;
import java.util.UUID;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The gauge as the UI sees it: computed at read time from the sample the capture path pushed, and
 * absent everywhere it would be a lie.
 */
@SpringBootTest
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class MigrationLagApiTest {

    private static final String BASE = "/api/v1/migration/tasks";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private MigrationTaskService taskService;

    @Autowired
    private MigrationLagRegistry lagRegistry;

    @Autowired
    private MigrationTaskMapper taskMapper;

    @Autowired
    private ConnectionInfoMapper connectionInfoMapper;

    @Autowired
    private io.opendrs.migration.mapper.TaskPipelineMapper pipelineMapper;

    @Test
    void runningIncrementalTaskReportsTheLiveGauge() throws Exception {
        long id = createTask(MigrationPhase.INCREMENTAL, TaskState.RUNNING);
        long pipelineId = pipelineOf(id);
        long now = System.currentTimeMillis();
        MigrationLagListener listener = lagRegistry.listenerFor(pipelineId);
        listener.onApplied(now - 1_500, now);
        listener.onHeartbeat(now);

        // Status and list read the same helper, so both must carry the number.
        mockMvc.perform(get(BASE + "/" + id + "/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1000))
                .andExpect(jsonPath("$.data.progress.lagMs", Matchers.notNullValue()));

        assertThat(taskService.list())
                .filteredOn(task -> task.id().equals(id))
                .singleElement()
                .satisfies(task -> assertThat(task.lagMs()).isNotNull());
    }

    @Test
    void fullLoadHasNothingToBeBehindOn() throws Exception {
        long id = createTask(MigrationPhase.FULL, TaskState.RUNNING);
        long now = System.currentTimeMillis();
        lagRegistry.listenerFor(pipelineOf(id)).onApplied(now - 900, now);

        mockMvc.perform(get(BASE + "/" + id + "/status"))
                .andExpect(jsonPath("$.data.progress.lagMs").value(Matchers.nullValue()));

        assertThat(taskService.list())
                .filteredOn(task -> task.id().equals(id))
                .singleElement()
                .satisfies(task -> assertThat(task.lagMs()).isNull());
    }

    /** A stopped pipeline keeps its last sample in memory, but the API must not replay it. */
    @Test
    void stoppedTaskStopsReporting() throws Exception {
        long id = createTask(MigrationPhase.INCREMENTAL, TaskState.RUNNING);
        long now = System.currentTimeMillis();
        lagRegistry.listenerFor(pipelineOf(id)).onApplied(now - 1_000, now);

        taskMapper.updatePhaseState(id, MigrationPhase.INCREMENTAL, TaskState.STOPPED);

        mockMvc.perform(get(BASE + "/" + id + "/status"))
                .andExpect(jsonPath("$.data.progress.lagMs").value(Matchers.nullValue()));
    }

    private long createTask(MigrationPhase phase, TaskState state) {
        long sourceId = dataSource("lag-source", DbType.MYSQL, "10.0.0.21", 3306, "hr", "cdc");
        long targetId = dataSource("lag-target", DbType.POSTGRESQL, "10.0.0.22", 5432, "appdb", "drs");

        MigrationTask task = new MigrationTask();
        task.setPublicId(UUID.randomUUID().toString());
        task.setName("lag-" + UUID.randomUUID());
        task.setMode(MigrationMode.FULL_AND_INCREMENTAL);
        task.setPhase(phase);
        task.setSourceConnectionId(sourceId);
        task.setTargetConnectionId(targetId);
        task.setOptionsJson(MigrationOptions.defaults());
        taskMapper.insert(task);
        taskMapper.updatePhaseState(task.getId(), phase, state);
        io.opendrs.migration.domain.TaskPipeline pipeline = new io.opendrs.migration.domain.TaskPipeline();
        pipeline.setTaskId(task.getId());
        pipeline.setSourceEndpointId(sourceId);
        pipeline.setTargetEndpointId(targetId);
        pipeline.setCaptureOptions(java.util.Map.of());
        pipeline.setReplayOptions(java.util.Map.of());
        pipelineMapper.insert(pipeline);
        return task.getId();
    }

    private long pipelineOf(long taskId) {
        return pipelineMapper.findByTaskId(taskId).getFirst().getId();
    }

    private long dataSource(String name, DbType type, String host, int port, String database, String username) {
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
        connection.setPassword("secret");
        connectionInfoMapper.insert(connection);
        return connection.getId();
    }
}
