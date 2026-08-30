package io.opendrs.debezium;

import static org.assertj.core.api.Assertions.assertThat;

import io.opendrs.migration.domain.ConnectionInfo;
import io.opendrs.migration.domain.DbType;
import io.opendrs.migration.domain.JobPhase;
import io.opendrs.migration.domain.JobState;
import io.opendrs.migration.domain.MigrationMode;
import io.opendrs.migration.domain.MigrationTask;
import io.opendrs.migration.mapper.ConnectionInfoMapper;
import io.opendrs.migration.mapper.DebeziumOffsetMapper;
import io.opendrs.migration.mapper.MigrationTaskMapper;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;

@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class TaskOffsetBackingStoreTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ConnectionInfoMapper connectionMapper;

    @Autowired
    private MigrationTaskMapper taskMapper;

    @Autowired
    private DebeziumOffsetMapper offsetMapper;

    @BeforeEach
    void registerDataSource() {
        EngineDataSourceHolder.initialize(jdbcTemplate.getDataSource());
    }

    @Test
    void upsertIsScopedByTaskIdAndDoesNotDeleteOtherTasks() throws Exception {
        long taskA = insertTask("offset-a");
        long taskB = insertTask("offset-b");

        TaskOffsetBackingStore storeA = newStore(taskA);
        TaskOffsetBackingStore storeB = newStore(taskB);
        storeA.start();
        storeB.start();
        try {
            storeA.set(Map.of(utf8("key-shared"), utf8("{\"file\":\"bin.0001\",\"pos\":100}")), null)
                    .get(5, TimeUnit.SECONDS);
            storeB.set(Map.of(utf8("key-shared"), utf8("{\"file\":\"bin.0009\",\"pos\":999,\"gtids\":\"a:1-2\"}")), null)
                    .get(5, TimeUnit.SECONDS);
            storeA.set(Map.of(utf8("key-a"), utf8("{\"file\":\"bin.0001\",\"pos\":200}")), null)
                    .get(5, TimeUnit.SECONDS);

            storeA.stop();
            storeB.stop();

            TaskOffsetBackingStore reloadA = newStore(taskA);
            TaskOffsetBackingStore reloadB = newStore(taskB);
            reloadA.start();
            reloadB.start();
            try {
                Map<ByteBuffer, ByteBuffer> fromA = reloadA.get(List.of(utf8("key-shared"), utf8("key-a")))
                        .get(5, TimeUnit.SECONDS);
                Map<ByteBuffer, ByteBuffer> fromB = reloadB.get(List.of(utf8("key-shared")))
                        .get(5, TimeUnit.SECONDS);

                assertThat(ByteBuffers.toUtf8(fromA.get(utf8("key-shared"))))
                        .isEqualTo("{\"file\":\"bin.0001\",\"pos\":100}");
                assertThat(ByteBuffers.toUtf8(fromA.get(utf8("key-a"))))
                        .isEqualTo("{\"file\":\"bin.0001\",\"pos\":200}");
                assertThat(ByteBuffers.toUtf8(fromB.get(utf8("key-shared"))))
                        .contains("bin.0009")
                        .contains("gtids");
            } finally {
                reloadA.stop();
                reloadB.stop();
            }
        } finally {
            storeA.stop();
            storeB.stop();
        }

        assertThat(offsetMapper.findByTaskId(taskA)).hasSize(2);
        assertThat(offsetMapper.findByTaskId(taskB)).hasSize(1);
        Integer otherTaskRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM debezium_offset WHERE task_id = ?", Integer.class, taskB);
        assertThat(otherTaskRows).isEqualTo(1);
    }

    private TaskOffsetBackingStore newStore(long taskId) {
        TaskOffsetBackingStore store = new TaskOffsetBackingStore();
        store.configure(Map.of(TaskOffsetBackingStore.TASK_ID_CONFIG, String.valueOf(taskId)));
        return store;
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

    private static ByteBuffer utf8(String value) {
        return ByteBuffer.wrap(value.getBytes(StandardCharsets.UTF_8));
    }
}
