package io.opendrs.debezium;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.opendrs.debezium.DebeziumEngineConfig.EngineSpec;
import io.opendrs.jdbc.metadata.Table;
import io.opendrs.jdbc.metadata.TableRef;
import io.opendrs.migration.domain.ConnectionInfo;
import io.opendrs.migration.domain.DbType;
import java.util.List;
import java.util.Properties;
import org.junit.jupiter.api.Test;

class DebeziumEngineConfigTest {

    @Test
    void schemaSnapshotUsesCustomNoDataFlagsAndStores() {
        Properties props = DebeziumEngineConfig.schemaSnapshot(spec(7L));

        assertThat(props.getProperty("connector.class")).isEqualTo(DebeziumEngineConfig.CONNECTOR_CLASS);
        assertThat(props.getProperty("snapshot.mode")).isEqualTo("custom");
        assertThat(props.getProperty("snapshot.mode.custom.name")).isEqualTo(SchemaOnlySnapshotter.NAME);
        assertThat(props.getProperty("opendrs.snapshot.mode")).isEqualTo("no_data");
        assertThat(props.getProperty("offset.storage")).isEqualTo(TaskOffsetBackingStore.class.getName());
        assertThat(props.getProperty(TaskOffsetBackingStore.TASK_ID_CONFIG)).isEqualTo("7");
        assertThat(props.getProperty("schema.history.internal")).isEqualTo(TaskSchemaHistory.class.getName());
        assertThat(props.getProperty(TaskSchemaHistory.TASK_ID_CONFIG)).isEqualTo("7");
        assertThat(props.getProperty("database.hostname")).isEqualTo("10.0.0.1");
        assertThat(props.getProperty("database.port")).isEqualTo("3306");
        assertThat(props.getProperty("database.user")).isEqualTo("cdc");
        assertThat(props.getProperty("database.password")).isEqualTo("secret");
        assertThat(props.getProperty("database.dbname")).isEqualTo("hr");
        assertThat(props.getProperty("database.server.id")).isEqualTo("85744");
        assertThat(props.getProperty("topic.prefix")).isEqualTo("opendrs.task.7");
        assertThat(props.getProperty("name")).isEqualTo("opendrs-task-7");
        assertThat(props.getProperty("table.include.list")).isEqualTo("hr.emp,hr.dept");
        assertThat(props.getProperty("database.include.list")).isEqualTo("hr");
        assertThat(props.getProperty("database.ssl.mode")).isEqualTo("disabled");
        assertThat(props.getProperty("notification.enabled.channels")).isEqualTo("sink");
        assertThat(props.getProperty("notification.sink.topic.name"))
                .isEqualTo(DebeziumEngineConfig.notificationTopic(7L));
    }

    @Test
    void incrementalReusesStoresAndSkipsSnapshot() {
        Properties schema = DebeziumEngineConfig.schemaSnapshot(spec(7L));
        Properties incremental = DebeziumEngineConfig.incremental(spec(7L));

        assertThat(incremental.getProperty("offset.storage")).isEqualTo(schema.getProperty("offset.storage"));
        assertThat(incremental.getProperty(TaskOffsetBackingStore.TASK_ID_CONFIG))
                .isEqualTo(schema.getProperty(TaskOffsetBackingStore.TASK_ID_CONFIG));
        assertThat(incremental.getProperty("schema.history.internal"))
                .isEqualTo(schema.getProperty("schema.history.internal"));
        assertThat(incremental.getProperty("topic.prefix")).isEqualTo(schema.getProperty("topic.prefix"));
        assertThat(incremental.getProperty("name")).isEqualTo(schema.getProperty("name"));
        assertThat(incremental.getProperty("snapshot.mode")).isEqualTo("custom");
        assertThat(incremental.getProperty("snapshot.mode.custom.name")).isEqualTo(IncrementalSnapshotter.NAME);
        assertThat(incremental.getProperty("notification.enabled.channels")).isNull();
    }

    @Test
    void requiresDatabaseServerIdFromOptions() {
        EngineSpec missing = new EngineSpec(1L, mysql(), tables(), null);
        assertThatThrownBy(() -> DebeziumEngineConfig.schemaSnapshot(missing))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("databaseServerId");
    }

    private static EngineSpec spec(long taskId) {
        return new EngineSpec(taskId, mysql(), tables(), 85744L);
    }

    private static ConnectionInfo mysql() {
        ConnectionInfo info = new ConnectionInfo();
        info.setType(DbType.MYSQL);
        info.setHost("10.0.0.1");
        info.setPort(3306);
        info.setDbName("hr");
        info.setUsername("cdc");
        info.setPassword("secret");
        info.setExtra(java.util.Map.of("useSsl", false));
        return info;
    }

    private static List<Table> tables() {
        return List.of(new Table(new TableRef("hr", "emp")), new Table(new TableRef("hr", "dept")));
    }
}
