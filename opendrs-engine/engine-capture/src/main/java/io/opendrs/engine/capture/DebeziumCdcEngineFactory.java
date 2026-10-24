package io.opendrs.engine.capture;

import io.debezium.engine.DebeziumEngine;
import io.debezium.engine.RecordChangeEvent;
import io.debezium.engine.format.ChangeEventFormat;
import io.debezium.engine.spi.OffsetCommitPolicy;
import io.debezium.embedded.Connect;
import io.opendrs.common.log.MigrationLogListener;
import io.opendrs.common.progress.MigrationLagListener;
import io.opendrs.jdbc.JdbcConnectionFactory;
import io.opendrs.engine.replay.ReplayConfig;
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
        ReplayApplyChangeConsumer applyConsumer = applyConsumer(spec, listener, lagListener);
        return build(
                DebeziumEngineConfig.capture(spec),
                spec.taskId(),
                new SnapshotCompletionConsumer(
                        new LagTrackingConsumer(applyConsumer, lagListener), onSnapshotCompleted, listener),
                applyConsumer);
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
            MigrationLagListener positionListener) {
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
        ReplayConfig config = configBuilder.build();
        return new ReplayApplyChangeConsumer(spec.taskId(), config, logListener, positionListener);
    }

    private MappedTableNamingStrategy specTableNaming(DebeziumEngineConfig.EngineSpec spec) {
        return new MappedTableNamingStrategy(mappingValidator, spec.tableSelection());
    }

    private CdcEngine build(
            java.util.Properties props,
            long taskId,
            DebeziumEngine.ChangeConsumer<RecordChangeEvent<SourceRecord>> consumer,
            AutoCloseable resource) {
        AtomicReference<Throwable> error = new AtomicReference<>();
        // Debezium instantiates offset/schema-history stores by class name; bind the metadata
        // DataSource for this engine run until the Engine stops. The key is the engine name, so
        // the two directions of one bidirectional task bind and unbind independently.
        String engineKey = props.getProperty("name");
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
                .using(OffsetCommitPolicy.always())
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
