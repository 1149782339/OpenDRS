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
    void captureUsesInitialSnapshotAndStores() {
        Properties props = DebeziumEngineConfig.capture(spec(7L));

        assertThat(props.getProperty("connector.class")).isEqualTo(DebeziumEngineConfig.CONNECTOR_CLASS);
        assertThat(props.getProperty("snapshot.mode")).isEqualTo(DebeziumEngineConfig.SNAPSHOT_MODE_INITIAL);
        assertThat(props.getProperty("snapshot.mode.custom.name")).isNull();
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
    void requiresDatabaseServerIdFromOptions() {
        EngineSpec missing = new EngineSpec(1L, mysql(), tables(), null);
        assertThatThrownBy(() -> DebeziumEngineConfig.capture(missing))
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
