package io.opendrs.engine.capture;

import io.debezium.engine.DebeziumEngine;
import io.debezium.engine.RecordChangeEvent;
import io.debezium.engine.format.ChangeEventFormat;
import io.debezium.engine.spi.OffsetCommitPolicy;
import io.debezium.embedded.Connect;
import io.opendrs.common.log.MigrationLogListener;
import io.opendrs.common.progress.FullLoadCheckpointListener;
import io.opendrs.common.progress.MigrationLagListener;
import io.opendrs.jdbc.JdbcConnectionFactory;
import io.opendrs.engine.replay.ReplayConfig;
import io.opendrs.engine.capture.transport.AppliedOffsetStore;
import io.opendrs.engine.capture.transport.JdbcAppliedOffsetStore;
import io.opendrs.engine.capture.transport.KafkaProduceChangeConsumer;
import io.opendrs.engine.capture.transport.KafkaReplayRunner;
import io.opendrs.engine.capture.transport.KafkaTransportConfig;
import io.opendrs.engine.capture.transport.TransportChannel;
import io.opendrs.engine.capture.transport.TransportSpec;
import io.opendrs.engine.replay.mapping.MappingValidator;
import io.opendrs.engine.replay.naming.DefaultColumnNamingStrategy;
import io.opendrs.engine.replay.naming.MappedTableNamingStrategy;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;
import org.apache.kafka.connect.source.SourceRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Builds {@link DebeziumEngine} via {@code DebeziumEngine.create} (AsyncEmbeddedEngine in 3.6).
 * Framework-free: the service layer wires the instances it needs and calls {@link #create}.
 */
public class DebeziumCdcEngineFactory implements CdcEngineFactory {

    private static final Logger log = LoggerFactory.getLogger(DebeziumCdcEngineFactory.class);

    /**
     * KAFKA capture only: {@link OffsetCommitPolicy#periodic} interval for flushing
     * {@code debezium_offsets} after successful produce. DIRECT keeps {@link OffsetCommitPolicy#always()}.
     */
    public static final String KAFKA_OFFSET_FLUSH_INTERVAL_MS = "3000";

    private final JdbcConnectionFactory connectionFactory;
    private final MappingValidator mappingValidator;
    private final DataSource metadataDataSource;

    public DebeziumCdcEngineFactory(
            JdbcConnectionFactory connectionFactory,
            MappingValidator mappingValidator,
            DataSource metadataDataSource) {
        this.connectionFactory = connectionFactory;
        this.mappingValidator = mappingValidator;
        this.metadataDataSource = metadataDataSource;
    }

    public CdcEngine create(DebeziumEngineConfig.EngineSpec spec) {
        return create(spec, (Runnable) null);
    }

    @Override
    public CdcEngine create(DebeziumEngineConfig.EngineSpec spec, Runnable onSnapshotCompleted) {
        return create(spec, onSnapshotCompleted, MigrationLogListener.NONE);
    }

    /**
     * @param logListener told when the snapshot turns into a stream and when a CDC batch is
     *     rejected; called on the Debezium thread, so the implementation must not block
     */
    public CdcEngine create(
            DebeziumEngineConfig.EngineSpec spec,
            Runnable onSnapshotCompleted,
            MigrationLogListener logListener) {
        return create(spec, onSnapshotCompleted, logListener, MigrationLagListener.NONE);
    }

    /**
     * @param lagListener told, after each committed batch, when the newest change happened in the
     *     source; also fed by heartbeats so an idle source is not mistaken for a stalled pipeline
     */
    public CdcEngine create(
            DebeziumEngineConfig.EngineSpec spec,
            Runnable onSnapshotCompleted,
            MigrationLogListener logListener,
            MigrationLagListener lagListener) {
        return create(spec, onSnapshotCompleted, logListener, lagListener, FullLoadCheckpointListener.NONE);
    }

    /**
     * @param checkpointListener told when the snapshot's table list resolves and whenever a
     *     table's full load commits on the target; called on the Debezium thread, so the
     *     implementation must not block
     */
    public CdcEngine create(
            DebeziumEngineConfig.EngineSpec spec,
            Runnable onSnapshotCompleted,
            MigrationLogListener logListener,
            MigrationLagListener lagListener,
            FullLoadCheckpointListener checkpointListener) {
        MigrationLogListener listener = logListener == null ? MigrationLogListener.NONE : logListener;
        if (spec.target() == null) {
            // Nothing is applied, so there is nothing to measure against.
            return build(
                    DebeziumEngineConfig.capture(spec),
                    spec.taskId(),
                    new SnapshotCompletionConsumer(
                            new LoggingChangeConsumer(spec.taskId()), onSnapshotCompleted, listener),
                    null);
        }
        TransportSpec transport = spec.transport() == null ? TransportSpec.direct() : spec.transport();
        if (transport.channel() == TransportChannel.KAFKA) {
            return buildKafka(
                    spec, onSnapshotCompleted, listener, lagListener, checkpointListener, transport);
        }
        ReplayApplyChangeConsumer applyConsumer = applyConsumer(spec, listener, lagListener, checkpointListener);
        return build(
                DebeziumEngineConfig.capture(spec),
                spec.taskId(),
                new SnapshotCompletionConsumer(applyConsumer, onSnapshotCompleted, listener),
                applyConsumer);
    }

    /**
     * KAFKA path: snapshot/FULL rows apply DIRECT (bypass Kafka); incremental envelopes are
     * produced to the pipeline topic (offsets mark produce progress). A sibling
     * {@link KafkaReplayRunner} consumes streaming records, applies via
     * {@link BatchApplyHandler}, and writes {@code pipeline_applied_offsets}.
     */
    private CdcEngine buildKafka(
            DebeziumEngineConfig.EngineSpec spec,
            Runnable onSnapshotCompleted,
            MigrationLogListener logListener,
            MigrationLagListener lagListener,
            FullLoadCheckpointListener checkpointListener,
            TransportSpec transport) {
        KafkaTransportConfig kafka = transport.kafka();
        ReplayConfig config = replayConfig(spec);
        // FULL: same apply→mark semantics as DIRECT (checkpoints land here).
        BatchApplyHandler snapshotApplyHandler = new BatchApplyHandler(
                spec.taskId(),
                spec.pipelineId(),
                config,
                logListener,
                lagListener,
                checkpointListener);
        KafkaProduceChangeConsumer produceConsumer =
                new KafkaProduceChangeConsumer(spec.taskId(), config, kafka, snapshotApplyHandler);
        // INCREMENTAL replay from Kafka; full-load checkpoints already handled above.
        BatchApplyHandler replayApplyHandler = new BatchApplyHandler(
                spec.taskId(),
                spec.pipelineId(),
                replayConfig(spec),
                logListener,
                lagListener,
                FullLoadCheckpointListener.NONE);
        AppliedOffsetStore appliedStore = new JdbcAppliedOffsetStore(metadataDataSource);
        KafkaReplayRunner replayRunner =
                new KafkaReplayRunner(spec.pipelineId(), kafka, replayApplyHandler, appliedStore);
        replayRunner.start();
        AutoCloseable resources = () -> {
            try {
                produceConsumer.close();
            }
            finally {
                replayRunner.close();
            }
        };
        // KAFKA: produce→mark still every batch; OffsetCommitPolicy.periodic flushes
        // debezium_offsets at most every 3s (async from the engine's POV relative to always()).
        java.util.Properties kafkaProps = DebeziumEngineConfig.capture(spec);
        kafkaProps.setProperty("offset.flush.interval.ms", KAFKA_OFFSET_FLUSH_INTERVAL_MS);
        return build(
                kafkaProps,
                spec.taskId(),
                new SnapshotCompletionConsumer(produceConsumer, onSnapshotCompleted, logListener),
                resources,
                OffsetCommitPolicy.periodic(kafkaProps));
    }

    /**
     * Same capture Engine as production, with a caller-supplied consumer. Tests use this to record
     * {@link SourceRecord}s without applying to a target.
     */
    public CdcEngine create(
            DebeziumEngineConfig.EngineSpec spec,
            DebeziumEngine.ChangeConsumer<RecordChangeEvent<SourceRecord>> consumer,
            Runnable onSnapshotCompleted) {
        return build(
                DebeziumEngineConfig.capture(spec),
                spec.taskId(),
                new SnapshotCompletionConsumer(consumer, onSnapshotCompleted, MigrationLogListener.NONE),
                null);
    }

    private ReplayApplyChangeConsumer applyConsumer(
            DebeziumEngineConfig.EngineSpec spec,
            MigrationLogListener logListener,
            MigrationLagListener positionListener,
            FullLoadCheckpointListener checkpointListener) {
        return new ReplayApplyChangeConsumer(
                spec.taskId(), spec.pipelineId(), replayConfig(spec),
                logListener, positionListener, checkpointListener);
    }

    private ReplayConfig replayConfig(DebeziumEngineConfig.EngineSpec spec) {
        ReplayConfig.Builder configBuilder = ReplayConfig.builder()
                // Stateless provider: every call opens a fresh physical connection, so each
                // parallel applier worker (via its own JdbcConnection) gets its own connection.
                .connectionProvider(() -> connectionFactory.open(spec.target()).unwrap())
                .tableNamingStrategy(specTableNaming(spec))
                .columnNamingStrategy(new DefaultColumnNamingStrategy())
                .applyDdlEnabled(true)
                .databaseDialectName(ReplayConfig.dialectNameFor(spec.target().getType()))
                .taskId(spec.taskId())
                .direction(spec.direction())
                .metrics(spec.metrics())
                .gtidLoop(spec.gtidLoop());
        if (spec.txApplierConcurrency() != null) {
            configBuilder.txApplierConcurrency(spec.txApplierConcurrency());
        }
        if (spec.tableApplierConcurrency() != null) {
            configBuilder.tableApplierConcurrency(spec.tableApplierConcurrency());
        }
        return configBuilder.build();
    }

    private MappedTableNamingStrategy specTableNaming(DebeziumEngineConfig.EngineSpec spec) {
        return new MappedTableNamingStrategy(mappingValidator, spec.tableSelection());
    }

    private CdcEngine build(
            java.util.Properties props,
            long taskId,
            DebeziumEngine.ChangeConsumer<RecordChangeEvent<SourceRecord>> consumer,
            AutoCloseable resource) {
        // DIRECT (and capture-only) paths: commit offsets as soon as the batch is marked.
        return build(props, taskId, consumer, resource, OffsetCommitPolicy.always());
    }

    private CdcEngine build(
            java.util.Properties props,
            long taskId,
            DebeziumEngine.ChangeConsumer<RecordChangeEvent<SourceRecord>> consumer,
            AutoCloseable resource,
            OffsetCommitPolicy commitPolicy) {
        AtomicReference<Throwable> error = new AtomicReference<>();
        // Debezium instantiates offset/schema-history stores by class name; bind the metadata
        // DataSource for this engine run until the Engine stops. The key is the engine name, so
        // the two directions of one bidirectional task bind and unbind independently.
        String engineKey = props.getProperty("name");
        // 诊断日志：这里记录的是实际写进 Debezium Properties 的值，以及 classpath 上真正加载的
        // debezium-connector-mysql 版本——两边对不上时（如胖 jar 内嵌旧版依赖）一眼可见。
        log.info("Debezium engine {} starting: snapshot.mode={}, debezium-connector-mysql={}, offsetCommit={}",
                engineKey, props.getProperty("snapshot.mode"), debeziumConnectorVersion(),
                commitPolicy.getClass().getSimpleName());
        TaskDataSourceRegistry.bind(engineKey, metadataDataSource);
        DebeziumEngine<RecordChangeEvent<SourceRecord>> engine = DebeziumEngine
                .create(ChangeEventFormat.of(Connect.class))
                .using(props)
                .notifying(consumer)
                .using((success, message, err) -> {
                    if (success) {
                        return;
                    }
                    if (err != null) {
                        error.set(err);
                    } else if (message != null && !message.isBlank()) {
                        error.set(new IllegalStateException(message));
                    }
                })
                .using(commitPolicy)
                .build();
        return new DebeziumCdcEngine(
                engine, error, taskId, resource, () -> TaskDataSourceRegistry.unbind(engineKey));
    }

    static final class DebeziumCdcEngine implements CdcEngine {

        private final DebeziumEngine<?> engine;
        private final AtomicReference<Throwable> error;
        private final long taskId;
        private final AutoCloseable resource;
        private final Runnable onClosed;
        /** {@code run()} and {@code stop()} both release the engine, whichever gets there first. */
        private final AtomicBoolean closed = new AtomicBoolean();

        DebeziumCdcEngine(
                DebeziumEngine<?> engine,
                AtomicReference<Throwable> error,
                long taskId,
                AutoCloseable resource,
                Runnable onClosed) {
            this.engine = engine;
            this.error = error;
            this.taskId = taskId;
            this.resource = resource;
            this.onClosed = onClosed;
        }

        @Override
        public void run() {
            try {
                engine.run();
                Throwable failure = error.get();
                if (failure != null) {
                    if (failure instanceof RuntimeException runtime) {
                        throw runtime;
                    }
                    // Debezium reports the real cause through the CompletionCallback, and it is
                    // usually a checked ExecutionException. Carry its text: the wrapper alone
                    // ("Engine failed") leaves the operator with nothing to act on.
                    throw new IllegalStateException(
                            "Debezium Engine failed for task " + taskId + ": " + describe(failure), failure);
                }
            } finally {
                release();
            }
        }

        @Override
        public void stop() {
            try {
                engine.close();
            } catch (IllegalStateException ex) {
                log.debug("Engine already stopping/stopped for task {}: {}", taskId, ex.getMessage());
            } catch (IOException ex) {
                throw new UncheckedIOException("Failed to stop Debezium Engine for task " + taskId, ex);
            } finally {
                release();
            }
        }

        /**
         * Closes the engine's side resources and unbinds the metadata DataSource exactly once: a
         * normal stop runs both paths (close() returns, then run() falls out of its finally), and a
         * stop before run() must still release them without ever starting the engine.
         */
        private void release() {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
            closeQuietly(resource);
            if (onClosed != null) {
                onClosed.run();
            }
        }
    }

    /**
     * Version of the debezium-connector-mysql artifact actually on the classpath. Reading it from
     * the code source location (rather than the pom) catches stale fat jars, where the packaged
     * dependency silently differs from what the build file declares. In a Spring Boot fat jar the
     * location looks like "jar:nested:/app/app.jar/!BOOT-INF/lib/debezium-connector-mysql-3.7.0.Final.jar!/".
     */
    private static String debeziumConnectorVersion() {
        try {
            java.net.URL location = io.debezium.connector.mysql.MySqlConnector.class
                    .getProtectionDomain()
                    .getCodeSource()
                    .getLocation();
            String path = java.net.URLDecoder.decode(location.getPath(), java.nio.charset.StandardCharsets.UTF_8);
            String artifact = "debezium-connector-mysql-";
            int start = path.lastIndexOf(artifact);
            if (start >= 0) {
                String rest = path.substring(start + artifact.length());
                int end = rest.indexOf(".jar");
                return end > 0 ? rest.substring(0, end) : rest;
            }
            return path;
        } catch (RuntimeException ex) {
            return "unknown (" + ex.getMessage() + ")";
        }
    }

    private static String describe(Throwable failure) {
        String message = failure.getMessage();
        return message == null || message.isBlank() ? failure.getClass().getName() : message;
    }

    private static void closeQuietly(AutoCloseable resource) {        if (resource == null) {
            return;
        }
        try {
            resource.close();
        } catch (Exception ex) {
            log.debug("Failed to close engine resource: {}", ex.getMessage());
        }
    }
}
