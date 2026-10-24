package io.opendrs.debezium;

import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;

import io.debezium.config.Configuration;
import io.debezium.relational.history.SchemaHistoryListener;
import io.opendrs.migration.api.request.MigrationOptions;
import io.opendrs.migration.domain.ConnectionInfo;
import io.opendrs.migration.domain.DbType;
import io.opendrs.migration.domain.MigrationPhase;
import io.opendrs.migration.domain.MigrationMode;
import io.opendrs.migration.domain.MigrationTask;
import io.opendrs.migration.mapper.ConnectionInfoMapper;
import io.opendrs.migration.mapper.MigrationTaskMapper;
import io.opendrs.engine.capture.TaskSchemaHistory;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;

@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class TaskSchemaHistoryTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ConnectionInfoMapper connectionMapper;

    @Autowired
    private MigrationTaskMapper taskMapper;

    @Autowired
    private io.opendrs.migration.mapper.TaskPipelineMapper pipelineMapper;

    @Test
    void appendsPerPipelineAndDoesNotClobberOtherPipeline() {
        long pipelineA = insertPipeline(insertTask("history-a"));
        long pipelineB = insertPipeline(insertTask("history-b"));

        TaskSchemaHistory historyA = history(pipelineA);
        TaskSchemaHistory historyB = history(pipelineB);
        historyA.start();
        historyB.start();

        Map<String, String> source = Map.of("server", "opendrs.pipeline." + pipelineA);
        Map<String, Object> position = Map.of("file", "bin.0001", "pos", 154);
        historyA.record(source, position, "hr", "CREATE TABLE emp (id INT)");
        historyA.record(source, Map.of("file", "bin.0001", "pos", 200), "hr", "ALTER TABLE emp ADD name VARCHAR(20)");
        historyB.record(Map.of("server", "opendrs.pipeline." + pipelineB), position, "hr", "CREATE TABLE other (id INT)");

        Integer countA = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM debezium_schema_histories WHERE pipeline_id = ?", Integer.class, pipelineA);
        Integer countB = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM debezium_schema_histories WHERE pipeline_id = ?", Integer.class, pipelineB);
        assertThat(countA).isEqualTo(2);
        assertThat(countB).isEqualTo(1);
        assertThat(historyA.exists()).isTrue();

        TaskSchemaHistory reloadA = history(pipelineA);
        reloadA.start();
        assertThat(reloadA.exists()).isTrue();
        String ddl = jdbcTemplate.queryForObject(
                "SELECT history_data FROM debezium_schema_histories WHERE pipeline_id = ? ORDER BY record_seq LIMIT 1",
                String.class,
                pipelineA);
        assertThat(ddl).contains("CREATE TABLE emp");
        assertThat(ddl).contains("\"source\"");
        assertThat(ddl).contains("\"position\"");
    }

    private TaskSchemaHistory history(long pipelineId) {
        TaskSchemaHistory history = new TaskSchemaHistory().withDataSource(jdbcTemplate.getDataSource());
        history.configure(
                Configuration.from(Map.of(TaskSchemaHistory.PIPELINE_ID_CONFIG, String.valueOf(pipelineId))),
                null,
                SchemaHistoryListener.NOOP,
                true);
        return history;
    }

    private long insertPipeline(long taskId) {
        MigrationTask task = taskMapper.findById(taskId);
        io.opendrs.migration.domain.TaskPipeline pipeline = new io.opendrs.migration.domain.TaskPipeline();
        pipeline.setTaskId(taskId);
        pipeline.setSourceEndpointId(task.getSourceConnectionId());
        pipeline.setTargetEndpointId(task.getTargetConnectionId());
        pipeline.setCaptureOptions(Map.of());
        pipeline.setReplayOptions(Map.of());
        pipelineMapper.insert(pipeline);
        return pipeline.getId();
    }

    private long insertTask(String name) {
        ConnectionInfo source = connection("src-" + name);
        ConnectionInfo target = connection("tgt-" + name);
        connectionMapper.insert(source);
        connectionMapper.insert(target);
        MigrationTask task = new MigrationTask();
        task.setPublicId(UUID.randomUUID().toString());
        task.setName(name);
        task.setMode(MigrationMode.FULL_AND_INCREMENTAL);
        task.setPhase(MigrationPhase.CREATED);
        task.setSourceConnectionId(source.getId());
        task.setTargetConnectionId(target.getId());
        task.setOptionsJson(new MigrationOptions(
                MigrationOptions.Topology.ONE_WAY,
                MigrationOptions.Selection.ofTables(
                        List.of(new io.opendrs.migration.api.request.SelectedTable("hr", "emp"))),
                MigrationOptions.FullSettings.defaults(),
                MigrationOptions.IncrementalSettings.defaults(),
                null));
        taskMapper.insert(task);
        return task.getId();
    }

    private static ConnectionInfo connection(String name) {
        ConnectionInfo info = new ConnectionInfo();
        info.setName(name);
        info.setType(DbType.MYSQL);
        info.setHost("localhost");
        info.setPort(3306);
        info.setDbName("hr");
        info.setUsername("u");
        info.setPassword("p");
        return info;
    }
}
