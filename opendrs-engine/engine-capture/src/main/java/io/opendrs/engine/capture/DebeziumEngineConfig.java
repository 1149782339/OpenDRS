package io.opendrs.engine.capture;

import io.opendrs.common.metadata.DatabaseType;
import io.opendrs.jdbc.JdbcConnectionConfig;
import io.opendrs.jdbc.metadata.Table;
import io.opendrs.engine.replay.GtidLoopConfig;
import io.opendrs.engine.replay.mapping.TableSelection;
import io.opendrs.engine.replay.metrics.ReplayDirection;
import io.opendrs.engine.replay.metrics.ReplayMetrics;
import java.util.List;
import java.util.Properties;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Builds Debezium Engine properties for the single MySQL capture Engine (snapshot then stream).
 *
 * <p>Selection is translated into Debezium's include/exclude lists, which are <em>regular
 * expressions</em> matched against the fully-qualified name, and which may not be set on both
 * sides at the same level. Each of the three shapes therefore picks one side:
 * <table>
 *   <caption>selection → Debezium filters</caption>
 *   <tr><th>shape</th><th>properties</th></tr>
 *   <tr><td>no include lists (whole instance)</td>
 *       <td>{@code database.exclude.list} / {@code table.exclude.list} only</td></tr>
 *   <tr><td>whole schemas</td>
 *       <td>{@code database.include.list} + {@code table.exclude.list}</td></tr>
 *   <tr><td>individual tables</td>
 *       <td>{@code table.include.list} only</td></tr>
 * </table>
 *
 * <p>The first row is the one that matters most: setting neither include list is what makes
 * Debezium follow a database or table created later. Writing an empty string instead would freeze
 * capture on the tables that existed when the task started.
 */
public final class DebeziumEngineConfig {

    public static final String CONNECTOR_CLASS = "io.debezium.connector.mysql.MySqlConnector";
    public static final String SNAPSHOT_MODE_INITIAL = "initial";
    public static final String NOTIFICATION_CHANNEL_SINK = "sink";

    /**
     * Ten seconds: often enough that the lag gauge can tell an idle source from a dead one (three
     * missed heartbeats is a minute), rare enough that the extra offset commits stay negligible.
     */
    static final String HEARTBEAT_INTERVAL_MS = "10000";

    private DebeziumEngineConfig() {
    }

    /**
     * Schema + data snapshot, then binlog streaming. Blocking initial snapshot vs binlog TTL is deferred.
     */
    public static Properties capture(EngineSpec spec) {
        Properties props = common(spec);
        props.setProperty("snapshot.mode", SNAPSHOT_MODE_INITIAL);
        props.setProperty("notification.enabled.channels", NOTIFICATION_CHANNEL_SINK);
        props.setProperty("notification.sink.topic.name", notificationTopic(spec.pipelineId()));
        // Parallel snapshot read: threads × multiplier ≈ the number of chunks Debezium splits
        // the table set into. Null keeps the connector defaults (serial snapshot).
        if (spec.snapshotThreads() != null) {
            props.setProperty("snapshot.max.threads", String.valueOf(spec.snapshotThreads()));
        }
        if (spec.snapshotThreadsMultiplier() != null) {
            props.setProperty(
                    "snapshot.max.threads.multiplier", String.valueOf(spec.snapshotThreadsMultiplier()));
        }
        return props;
    }

    static Properties common(EngineSpec spec) {
        if (spec.source().getType() != DatabaseType.MYSQL) {
            throw new IllegalStateException(
                    "v1 CDC supports MySQL source only, got " + spec.source().getType());
        }
        Long serverId = spec.databaseServerId();
        if (serverId == null) {
            throw new IllegalStateException("options.databaseServerId is required for Debezium (database.server.id)");
        }
        String engineName = engineName(spec.pipelineId());
        String topicPrefix = topicPrefix(spec.pipelineId());
        Properties props = new Properties();
        props.setProperty("name", engineName);
        props.setProperty("connector.class", CONNECTOR_CLASS);
        props.setProperty("offset.storage", TaskOffsetBackingStore.class.getName());
        props.setProperty(TaskOffsetBackingStore.TASK_ID_CONFIG, String.valueOf(spec.taskId()));
        props.setProperty(TaskOffsetBackingStore.PIPELINE_ID_CONFIG, String.valueOf(spec.pipelineId()));
        props.setProperty(TaskOffsetBackingStore.ENGINE_KEY_CONFIG, engineName);
        props.setProperty("offset.flush.interval.ms", "1000");
        props.setProperty("schema.history.internal", TaskSchemaHistory.class.getName());
        props.setProperty(TaskSchemaHistory.PIPELINE_ID_CONFIG, String.valueOf(spec.pipelineId()));
        props.setProperty(TaskSchemaHistory.ENGINE_KEY_CONFIG, engineName);
        props.setProperty("schema.history.internal.connector.class", CONNECTOR_CLASS);
        props.setProperty("schema.history.internal.connector.id", engineName);
        props.setProperty("schema.history.internal.store.only.captured.tables.ddl", "true");
        props.setProperty("topic.prefix", topicPrefix);
        props.setProperty("database.server.id", String.valueOf(serverId));
        applySelection(props, spec);
        props.setProperty("tombstones.on.delete", "false");
        props.setProperty("include.schema.changes", "true");
        props.setProperty("snapshot.locking.mode", "none");
        // A quiet source emits nothing, so "time since the last applied change" would grow without
        // bound and a perfectly caught-up task would look stuck. A heartbeat proves the connector is
        // alive (and keeps the offset fresh) while no data flows; the sink drops these records.
        props.setProperty("heartbeat.interval.ms", HEARTBEAT_INTERVAL_MS);
        // Attach the source transaction id to every data record. The sink's replay-TPS metric
        // dedups by it (one source transaction of N rows counts once, not N); without it the
        // metric silently degrades to rows/sec and looks identical to the QPS chart.
        props.setProperty("provide.transaction.metadata", "true");
        DebeziumSourceFields.of(spec.source()).forEach(props::setProperty);
        return props;
    }

    static void applySelection(Properties props, EngineSpec spec) {
        TableSelection selection = spec.tableSelection() == null
                ? TableSelection.all()
                : spec.tableSelection();
        if (selection.isAll()) {
            setIfPresent(props, "database.exclude.list", excludeSchemas(selection));
            setIfPresent(props, "table.exclude.list", excludeTables(selection));
            return;
        }
        if (selection.isSchemaLevel()) {
            props.setProperty("database.include.list", includeSchemas(selection));
            setIfPresent(props, "table.exclude.list", excludeTables(selection));
            return;
        }
        // Table-level: already resolved and glob-free by the expander, so pass literals only.
        props.setProperty("table.include.list", tableIncludeList(spec.tables()));
    }

    /**
     * Engine name is the runtime namespace of one pipeline: the metadata DataSource registry binds
     * and unbinds by it, so it must be unique per engine run.
     */
    public static String engineName(long pipelineId) {
        return "opendrs-pipeline-" + pipelineId;
    }

    /**
     * The topic prefix is Debezium's logical server name and therefore the source partition of
     * every offset: distinct prefixes give the pipelines of one task disjoint offset rows in
     * {@code debezium_offsets} without any storage change.
     */
    public static String topicPrefix(long pipelineId) {
        return "opendrs.pipeline." + pipelineId;
    }

    public static String notificationTopic(long pipelineId) {
        return topicPrefix(pipelineId) + ".notification";
    }

    static String tableIncludeList(List<Table> tables) {
        return tables.stream()
                .map(table -> literal(table.ref().schema()) + "\\." + literal(table.ref().table()))
                .collect(Collectors.joining(","));
    }

    static String includeSchemas(TableSelection selection) {
        return selection.schemas().stream().map(DebeziumEngineConfig::literal).collect(Collectors.joining(","));
    }

    static String excludeSchemas(TableSelection selection) {
        return selection.excludeSchemas().stream().map(DebeziumEngineConfig::literal).collect(Collectors.joining(","));
    }

    /**
     * Debezium matches these patterns against {@code database.table}. Both parts are quoted, so an
     * excluded table is excluded by its exact name and nothing else.
     */
    static String excludeTables(TableSelection selection) {
        return selection.excludeTables().stream()
                .map(table -> literal(table.getSchema()) + "\\." + literal(table.getTable()))
                .collect(Collectors.joining(","));
    }

    /** Debezium patterns are regexes: a literal identifier must be quoted, dots included. */
    private static String literal(String name) {
        return Pattern.quote(name);
    }

    private static void setIfPresent(Properties props, String key, String value) {
        if (value != null && !value.isBlank()) {
            props.setProperty(key, value);
        }
    }

    /**
     * Neutral execution descriptor: everything the capture mechanism needs, expressed in
     * framework-free types (jdbc configs, jdbc metadata, engine mapping types). The service
     * boundary builds this from its own job/connection records.
     *
     * <p>{@code taskId} is bookkeeping (log messages, the failed-task wrapper); {@code pipelineId}
     * is the runtime namespace — engine name, topic prefix and therefore the offset/schema-history
     * rows all key off it, so two pipelines of one task never touch each other's state.
     *
     * <p>{@code tables} is the expanded selection (empty in the whole-instance mode, where the
     * connector filters by exclusion instead); {@code tableSelection} is the unresolved form the
     * applier needs to resolve schema/table renames.
     *
     * @param gtidLoop loop-prevention wiring of this pipeline; null keeps one-way behaviour
     *     (no GTID tagging, no capture filtering)
     * @param direction which physical direction this pipeline carries; tags the sink metrics,
     *     null defaults to {@link ReplayDirection#FORWARD}
     */
    public record EngineSpec(
            long taskId,
            long pipelineId,
            JdbcConnectionConfig source,
            List<Table> tables,
            Long databaseServerId,
            JdbcConnectionConfig target,
            TableSelection tableSelection,
            ReplayMetrics metrics,
            GtidLoopConfig gtidLoop,
            ReplayDirection direction,
            /**
             * Worker count of the incremental transaction applier; null keeps the sink's
             * default. The service wires {@code options.incremental.replayConcurrency}.
             */
            Integer txApplierConcurrency,
            /**
             * Worker count of the full-load table applier; null keeps the sink's default. The
             * service wires {@code options.full.importConcurrency}.
             */
            Integer tableApplierConcurrency,
            /**
             * Thread count of the Debezium snapshot reader, wired to
             * {@code snapshot.max.threads}; null keeps the connector default (serial snapshot).
             * The service wires {@code options.full.exportConcurrency}.
             */
            Integer snapshotThreads,
            /**
             * Debezium {@code snapshot.max.threads.multiplier}: snapshot chunks per thread, so
             * 实际分片数 ≈ 并发线程数 × 分片倍率. The service wires
             * {@code options.full.chunkMultiplier}. Null keeps the connector default.
             */
            Integer snapshotThreadsMultiplier) {

        public EngineSpec {
            direction = direction == null ? ReplayDirection.FORWARD : direction;
        }

        /** Compact form for callers without a persisted pipeline; the task id stands in as pipeline id. */
        public EngineSpec(long taskId, JdbcConnectionConfig source, List<Table> tables, Long databaseServerId) {
            this(taskId, taskId, source, tables, databaseServerId, null, null,
                    ReplayMetrics.NONE, null, ReplayDirection.FORWARD, null, null, null, null);
        }

        public EngineSpec(
                long taskId,
                JdbcConnectionConfig source,
                List<Table> tables,
                Long databaseServerId,
                JdbcConnectionConfig target,
                TableSelection tableSelection) {
            this(taskId, taskId, source, tables, databaseServerId, target, tableSelection,
                    ReplayMetrics.NONE, null, ReplayDirection.FORWARD, null, null, null, null);
        }

        public EngineSpec(
                long taskId,
                JdbcConnectionConfig source,
                List<Table> tables,
                Long databaseServerId,
                JdbcConnectionConfig target,
                TableSelection tableSelection,
                ReplayMetrics metrics) {
            this(taskId, taskId, source, tables, databaseServerId, target, tableSelection,
                    metrics, null, ReplayDirection.FORWARD, null, null, null, null);
        }

        public EngineSpec(
                long taskId,
                long pipelineId,
                JdbcConnectionConfig source,
                List<Table> tables,
                Long databaseServerId,
                JdbcConnectionConfig target,
                TableSelection tableSelection,
                ReplayMetrics metrics) {
            this(taskId, pipelineId, source, tables, databaseServerId, target, tableSelection,
                    metrics, null, ReplayDirection.FORWARD, null, null, null, null);
        }

        public EngineSpec(
                long taskId,
                long pipelineId,
                JdbcConnectionConfig source,
                List<Table> tables,
                Long databaseServerId,
                JdbcConnectionConfig target,
                TableSelection tableSelection,
                ReplayMetrics metrics,
                GtidLoopConfig gtidLoop) {
            this(taskId, pipelineId, source, tables, databaseServerId, target, tableSelection,
                    metrics, gtidLoop, ReplayDirection.FORWARD, null, null, null, null);
        }
    }
}
