package io.opendrs.debezium;

import io.opendrs.jdbc.dialect.DbDialect;
import io.opendrs.jdbc.dialect.DbDialects;
import io.opendrs.jdbc.metadata.Table;
import io.opendrs.migration.api.request.MigrationOptions;
import io.opendrs.migration.domain.ConnectionInfo;
import io.opendrs.migration.domain.DbType;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Builds Debezium Engine properties for the two MySQL Engine rounds.
 */
public final class DebeziumEngineConfig {

    public static final String CONNECTOR_CLASS = "io.debezium.connector.mysql.MySqlConnector";
    public static final String SNAPSHOT_MODE_NO_DATA = "no_data";
    public static final String SNAPSHOT_MODE_CUSTOM = "custom";
    public static final String NOTIFICATION_CHANNEL_SINK = "sink";

    private DebeziumEngineConfig() {
    }

    public static Properties schemaSnapshot(EngineSpec spec) {
        Properties props = common(spec);
        // no_data (schema yes, data no) is the MySQL 3.6 mode; recovery is wrong here.
        // Built-in no_data still streams, so SCHEMA_SNAPSHOT uses a custom Snapshotter with
        // shouldStream=false. AsyncEmbeddedEngine still polls after that, so we enable the
        // sink notification channel and stop on Initial Snapshot COMPLETED.
        props.setProperty("snapshot.mode", SNAPSHOT_MODE_CUSTOM);
        props.setProperty("snapshot.mode.custom.name", SchemaOnlySnapshotter.NAME);
        props.setProperty("opendrs.snapshot.mode", SNAPSHOT_MODE_NO_DATA);
        props.setProperty("notification.enabled.channels", NOTIFICATION_CHANNEL_SINK);
        props.setProperty("notification.sink.topic.name", notificationTopic(spec.taskId()));
        return props;
    }

    public static Properties incremental(EngineSpec spec) {
        Properties props = common(spec);
        props.setProperty("snapshot.mode", SNAPSHOT_MODE_CUSTOM);
        props.setProperty("snapshot.mode.custom.name", IncrementalSnapshotter.NAME);
        return props;
    }

    static Properties common(EngineSpec spec) {
        if (spec.source().getType() != DbType.MYSQL) {
            throw new IllegalStateException(
                    "v1 CDC supports MySQL source only, got " + spec.source().getType());
        }
        Long serverId = spec.databaseServerId();
        if (serverId == null) {
            throw new IllegalStateException(
                    "options.databaseServerId is required for Debezium (database.server.id)");
        }
        String engineName = engineName(spec.taskId());
        String topicPrefix = topicPrefix(spec.taskId());
        Properties props = new Properties();
        props.setProperty("name", engineName);
        props.setProperty("connector.class", CONNECTOR_CLASS);
        props.setProperty("offset.storage", TaskOffsetBackingStore.class.getName());
        props.setProperty(TaskOffsetBackingStore.TASK_ID_CONFIG, String.valueOf(spec.taskId()));
        props.setProperty("offset.flush.interval.ms", "1000");
        props.setProperty("schema.history.internal", TaskSchemaHistory.class.getName());
        props.setProperty(TaskSchemaHistory.TASK_ID_CONFIG, String.valueOf(spec.taskId()));
        props.setProperty("schema.history.internal.connector.class", CONNECTOR_CLASS);
        props.setProperty("schema.history.internal.connector.id", engineName);
        props.setProperty("schema.history.internal.store.only.captured.tables.ddl", "true");
        props.setProperty("topic.prefix", topicPrefix);
        props.setProperty("database.server.id", String.valueOf(serverId));
        props.setProperty("table.include.list", tableIncludeList(spec.tables()));
        props.setProperty("database.include.list", databaseIncludeList(spec.tables()));
        props.setProperty("tombstones.on.delete", "false");
        props.setProperty("include.schema.changes", "true");
        props.setProperty("snapshot.locking.mode", "none");
        DbDialect dialect = DbDialects.of(spec.source().getType());
        dialect.debeziumSourceFields(spec.source()).forEach(props::setProperty);
        return props;
    }

    public static String engineName(long taskId) {
        return "opendrs-task-" + taskId;
    }

    public static String topicPrefix(long taskId) {
        return "opendrs.task." + taskId;
    }

    public static String notificationTopic(long taskId) {
        return topicPrefix(taskId) + ".notification";
    }

    static String tableIncludeList(List<Table> tables) {
        return tables.stream()
                .map(table -> table.ref().schema() + "." + table.ref().table())
                .collect(Collectors.joining(","));
    }

    static String databaseIncludeList(List<Table> tables) {
        Set<String> databases = new LinkedHashSet<>();
        for (Table table : tables) {
            databases.add(table.ref().schema());
        }
        return String.join(",", databases);
    }

    public record EngineSpec(
            long taskId, ConnectionInfo source, List<Table> tables, Long databaseServerId) {

        public static EngineSpec of(
                long taskId, ConnectionInfo source, List<Table> tables, MigrationOptions options) {
            Long serverId = options == null ? null : options.databaseServerId();
            return new EngineSpec(taskId, source, tables, serverId);
        }
    }
}
