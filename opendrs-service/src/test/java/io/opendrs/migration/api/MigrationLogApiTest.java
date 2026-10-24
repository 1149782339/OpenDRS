package io.opendrs.migration.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.opendrs.common.log.MigrationLogLevel;
import io.opendrs.migration.api.request.MigrationOptions;
import io.opendrs.migration.domain.ConnectionInfo;
import io.opendrs.migration.domain.DbType;
import io.opendrs.migration.domain.MigrationPhase;
import io.opendrs.migration.domain.MigrationMode;
import io.opendrs.migration.domain.MigrationTask;
import io.opendrs.migration.mapper.ConnectionInfoMapper;
import io.opendrs.migration.mapper.MigrationTaskMapper;
import io.opendrs.migration.service.MigrationLogService;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * The log endpoint against the real schema: the SQL is what is under test here (level filter,
 * limit/offset, the {@code total} the pager counts), not the buffering, which
 * {@code MigrationLogServiceTest} covers with a mocked mapper.
 */
@SpringBootTest
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class MigrationLogApiTest {

    private static final String BASE = "/api/v1/migration/tasks";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private MigrationLogService logService;

    @Autowired
    private MigrationTaskMapper taskMapper;

    @Autowired
    private ConnectionInfoMapper connectionInfoMapper;

    private long taskId;

    @BeforeEach
    void seedTask() {
        if (taskId != 0) {
            return;
        }
        taskId = createTask("log-task-" + UUID.randomUUID());

        // 26 lines: five of them WARNING (i % 5 == 0) plus one ERROR, so the filter has something
        // to prove and the pager needs a second page.
        for (int i = 0; i < 25; i++) {
            logService.log(
                    taskId,
                    i % 5 == 0 ? MigrationLogLevel.WARNING : MigrationLogLevel.INFO,
                    "line " + i);
        }
        logService.log(taskId, MigrationLogLevel.ERROR, "boom");
        logService.flush();
    }

    @Test
    void firstPageHoldsTwentyAndCountsEverything() throws Exception {
        logs("?page=1&size=20")
                .andExpect(jsonPath("$.data.total").value(26))
                .andExpect(jsonPath("$.data.page").value(1))
                .andExpect(jsonPath("$.data.size").value(20))
                .andExpect(jsonPath("$.data.items.length()").value(20))
                .andExpect(jsonPath("$.data.items[0].message").value("line 0"))
                .andExpect(jsonPath("$.data.items[0].loggedAt").isNotEmpty());
    }

    @Test
    void secondPageHoldsTheRest() throws Exception {
        logs("?page=2&size=20")
                .andExpect(jsonPath("$.data.items.length()").value(6))
                .andExpect(jsonPath("$.data.total").value(26));
    }

    /** Filtering in SQL is the point: total must describe the filter, not the task. */
    @Test
    void levelFilterNarrowsBothRowsAndTotal() throws Exception {
        logs("?level=ERROR")
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.items[0].level").value("ERROR"))
                .andExpect(jsonPath("$.data.items[0].message").value("boom"));

        logs("?level=WARNING").andExpect(jsonPath("$.data.total").value(5));
    }

    @Test
    void emptyLevelMeansEveryLevel() throws Exception {
        logs("?level=").andExpect(jsonPath("$.data.total").value(26));
    }

    @Test
    void unknownTaskIsNotFound() throws Exception {
        mockMvc.perform(get(BASE + "/999999/logs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1002));
    }

    /** Deleting a task must take its log with it, otherwise the foreign key blocks the delete. */
    @Test
    void deletingTheTaskTakesItsLogsWithIt() throws Exception {
        long other = createTask("log-delete-" + UUID.randomUUID());
        logService.log(other, MigrationLogLevel.INFO, "about to disappear");
        logService.flush();

        mockMvc.perform(get(BASE + "/" + other + "/logs"))
                .andExpect(jsonPath("$.data.total").value(1));

        logService.deleteByTaskId(other);

        mockMvc.perform(get(BASE + "/" + other + "/logs"))
                .andExpect(jsonPath("$.data.total").value(0));
    }

    private long createTask(String name) {
        long sourceId = dataSource("log-source", DbType.MYSQL, "10.0.0.11", 3306, "hr", "cdc");
        long targetId = dataSource("log-target", DbType.POSTGRESQL, "10.0.0.12", 5432, "appdb", "drs");

        MigrationTask task = new MigrationTask();
        task.setPublicId(UUID.randomUUID().toString());
        task.setName(name);
        task.setMode(MigrationMode.FULL_AND_INCREMENTAL);
        task.setPhase(MigrationPhase.CREATED);
        task.setSourceConnectionId(sourceId);
        task.setTargetConnectionId(targetId);
        task.setOptionsJson(MigrationOptions.defaults());
        taskMapper.insert(task);
        return task.getId();
    }

    private ResultActions logs(String query) throws Exception {
        return mockMvc.perform(get(BASE + "/" + taskId + "/logs" + query))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1000));
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
