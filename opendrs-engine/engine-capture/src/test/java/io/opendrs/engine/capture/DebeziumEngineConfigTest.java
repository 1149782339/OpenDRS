package io.opendrs.engine.capture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.opendrs.common.metadata.DatabaseType;
import io.opendrs.common.metadata.TableId;
import io.opendrs.engine.capture.DebeziumEngineConfig.EngineSpec;
import io.opendrs.engine.replay.mapping.TableSelection;
import io.opendrs.jdbc.JdbcConnectionConfig;
import io.opendrs.jdbc.metadata.Table;
import io.opendrs.jdbc.metadata.TableRef;
import java.util.List;
import java.util.Properties;
import org.junit.jupiter.api.Test;

class DebeziumEngineConfigTest {

    @Test
    void captureUsesInitialSnapshotAndStores() {
        Properties props = DebeziumEngineConfig.capture(spec(7L, TableSelection.ofTables(
                List.of(TableId.of("hr", "emp"), TableId.of("hr", "dept")))));

        assertThat(props.getProperty("connector.class")).isEqualTo(DebeziumEngineConfig.CONNECTOR_CLASS);
        assertThat(props.getProperty("snapshot.mode")).isEqualTo(DebeziumEngineConfig.SNAPSHOT_MODE_INITIAL);
        assertThat(props.getProperty("snapshot.mode.custom.name")).isNull();
        assertThat(props.getProperty("offset.storage")).isEqualTo(TaskOffsetBackingStore.class.getName());
        assertThat(props.getProperty(TaskOffsetBackingStore.TASK_ID_CONFIG)).isEqualTo("7");
        assertThat(props.getProperty("schema.history.internal")).isEqualTo(TaskSchemaHistory.class.getName());
        assertThat(props.getProperty(TaskSchemaHistory.PIPELINE_ID_CONFIG)).isEqualTo("7");
        assertThat(props.getProperty("database.hostname")).isEqualTo("10.0.0.1");
        assertThat(props.getProperty("database.port")).isEqualTo("3306");
        assertThat(props.getProperty("database.user")).isEqualTo("cdc");
        assertThat(props.getProperty("database.password")).isEqualTo("secret");
        assertThat(props.getProperty("database.dbname")).isEqualTo("hr");
        assertThat(props.getProperty("database.server.id")).isEqualTo("85744");
        assertThat(props.getProperty("topic.prefix")).isEqualTo("opendrs.pipeline.7");
        assertThat(props.getProperty("name")).isEqualTo("opendrs-pipeline-7");
        assertThat(props.getProperty("database.ssl.mode")).isEqualTo("disabled");
        assertThat(props.getProperty("notification.enabled.channels")).isEqualTo("sink");
        assertThat(props.getProperty("notification.sink.topic.name"))
                .isEqualTo(DebeziumEngineConfig.notificationTopic(7L));
        // Replay TPS dedups by source transaction id; without this it degrades to rows/sec.
        assertThat(props.getProperty("provide.transaction.metadata")).isEqualTo("true");
    }

    @Test
    void individualTablesAreListedLiterallyAndExcludesAreSubtractedUpstream() {
        Properties props = DebeziumEngineConfig.capture(spec(7L, TableSelection.ofTables(
                List.of(TableId.of("hr", "emp"), TableId.of("hr", "dept")))));

        assertThat(props.getProperty("table.include.list"))
                .isEqualTo("\\Qhr\\E\\.\\Qemp\\E,\\Qhr\\E\\.\\Qdept\\E");
        assertThat(props.getProperty("database.include.list")).isNull();
        assertThat(props.getProperty("table.exclude.list")).isNull();
    }

    @Test
    void wholeSchemasIncludeTheDatabaseAndExcludeTables() {
        TableSelection selection = new TableSelection(
                List.of("hr", "sales"),
                null,
                null,
                List.of(TableId.of("hr", "tmp_skip"), TableId.of("sales", "audit")),
                null);
        Properties props = DebeziumEngineConfig.capture(spec(7L, selection));

        assertThat(props.getProperty("database.include.list")).isEqualTo("\\Qhr\\E,\\Qsales\\E");
        assertThat(props.getProperty("table.exclude.list"))
                .isEqualTo("\\Qhr\\E\\.\\Qtmp_skip\\E,\\Qsales\\E\\.\\Qaudit\\E");
        assertThat(props.getProperty("table.include.list")).isNull();
        assertThat(props.getProperty("database.exclude.list")).isNull();
    }

    /** The whole point of the "everything" mode: no include list, so new databases stay in scope. */
    @Test
    void wholeInstanceSetsNoIncludeListAtAll() {
        Properties props = DebeziumEngineConfig.capture(spec(7L, TableSelection.all()));

        assertThat(props.getProperty("table.include.list")).isNull();
        assertThat(props.getProperty("database.include.list")).isNull();
        assertThat(props.getProperty("table.exclude.list")).isNull();
        assertThat(props.getProperty("database.exclude.list")).isNull();
    }

    @Test
    void wholeInstanceCanExcludeSchemasAndTables() {
        TableSelection selection = new TableSelection(
                null, null, List.of("tmp"), List.of(TableId.of("hr", "tmp_skip")), null);
        Properties props = DebeziumEngineConfig.capture(spec(7L, selection));

        assertThat(props.getProperty("database.exclude.list")).isEqualTo("\\Qtmp\\E");
        assertThat(props.getProperty("table.exclude.list")).isEqualTo("\\Qhr\\E\\.\\Qtmp_skip\\E");
        assertThat(props.getProperty("table.include.list")).isNull();
        assertThat(props.getProperty("database.include.list")).isNull();
    }

    @Test
    void nullSelectionIsTheWholeInstance() {
        Properties props = DebeziumEngineConfig.capture(
                new EngineSpec(7L, mysql(), tables(), 85744L, null, null));

        assertThat(props.getProperty("table.include.list")).isNull();
        assertThat(props.getProperty("database.include.list")).isNull();
    }

    @Test
    void requiresDatabaseServerIdFromOptions() {
        EngineSpec missing = new EngineSpec(1L, mysql(), tables(), null, null, TableSelection.all());
        assertThatThrownBy(() -> DebeziumEngineConfig.capture(missing))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("databaseServerId");
    }

    private static EngineSpec spec(long taskId, TableSelection selection) {
        return new EngineSpec(taskId, mysql(), tables(), 85744L, null, selection);
    }

    private static JdbcConnectionConfig mysql() {
        return JdbcConnectionConfig.builder()
                .type(DatabaseType.MYSQL)
                .host("10.0.0.1")
                .port(3306)
                .database("hr")
                .username("cdc")
                .password("secret")
                .extras(java.util.Map.of("useSsl", false))
                .build();
    }

    private static List<Table> tables() {
        return List.of(new Table(new TableRef("hr", "emp")), new Table(new TableRef("hr", "dept")));
    }
}
