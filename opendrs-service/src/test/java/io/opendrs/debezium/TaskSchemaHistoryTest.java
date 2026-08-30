package io.opendrs.debezium;

import static org.assertj.core.api.Assertions.assertThat;

import io.debezium.config.Configuration;
import io.debezium.relational.history.SchemaHistoryListener;
import io.opendrs.migration.domain.ConnectionInfo;
import io.opendrs.migration.domain.DbType;
import io.opendrs.migration.domain.JobPhase;
import io.opendrs.migration.domain.MigrationMode;
import io.opendrs.migration.domain.MigrationTask;
import io.opendrs.migration.mapper.ConnectionInfoMapper;
import io.opendrs.migration.mapper.MigrationTaskMapper;
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

    @BeforeEach
    void registerDataSource() {
        EngineDataSourceHolder.initialize(jdbcTemplate.getDataSource());
    }

    @Test
    void appendsPerTaskAndDoesNotClobberOtherTask() {
        long taskA = insertTask("history-a");
        long taskB = insertTask("history-b");

        TaskSchemaHistory historyA = history(taskA);
        TaskSchemaHistory historyB = history(taskB);
        historyA.start();
        historyB.start();

        Map<String, String> source = Map.of("server", "opendrs.task." + taskA);
        Map<String, Object> position = Map.of("file", "bin.0001", "pos", 154);
        historyA.record(source, position, "hr", "CREATE TABLE emp (id INT)");
        historyA.record(source, Map.of("file", "bin.0001", "pos", 200), "hr", "ALTER TABLE emp ADD name VARCHAR(20)");
        historyB.record(Map.of("server", "opendrs.task." + taskB), position, "hr", "CREATE TABLE other (id INT)");

        Integer countA = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM debezium_schema_history WHERE task_id = ?", Integer.class, taskA);
        Integer countB = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM debezium_schema_history WHERE task_id = ?", Integer.class, taskB);
        assertThat(countA).isEqualTo(2);
        assertThat(countB).isEqualTo(1);
        assertThat(historyA.exists()).isTrue();

        TaskSchemaHistory reloadA = history(taskA);
        reloadA.start();
        assertThat(reloadA.exists()).isTrue();
        String ddl = jdbcTemplate.queryForObject(
                "SELECT history_data FROM debezium_schema_history WHERE task_id = ? ORDER BY record_seq LIMIT 1",
                String.class,
                taskA);
        assertThat(ddl).contains("CREATE TABLE emp");
        assertThat(ddl).contains("\"source\"");
        assertThat(ddl).contains("\"position\"");
    }

    private TaskSchemaHistory history(long taskId) {
        TaskSchemaHistory history = new TaskSchemaHistory();
        history.configure(
                Configuration.from(Map.of(TaskSchemaHistory.TASK_ID_CONFIG, String.valueOf(taskId))),
                null,
                SchemaHistoryListener.NOOP,
                true);
        return history;
    }

    private long insertTask(String name) {
        ConnectionInfo source = connection("src-" + name);
        ConnectionInfo target = connection("tgt-" + name);
        connectionMapper.insert(source);
        connectionMapper.insert(target);
        MigrationTask task = new MigrationTask();
        task.setName(name);
        task.setMode(MigrationMode.FULL_AND_INCREMENTAL);
        task.setJobPhase(JobPhase.CREATED);
        task.setSourceConnectionId(source.getId());
        task.setTargetConnectionId(target.getId());
        task.setTablesJson(new io.opendrs.migration.api.request.TableSelection(
                List.of(new io.opendrs.migration.api.request.SchemaObject("hr", List.of("emp"), null, null)),
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
