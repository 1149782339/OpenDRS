package io.opendrs.debezium;

import io.debezium.engine.DebeziumEngine;
import io.debezium.engine.RecordChangeEvent;
import io.debezium.engine.format.ChangeEventFormat;
import io.debezium.engine.spi.OffsetCommitPolicy;
import io.debezium.embedded.Connect;
import io.opendrs.jdbc.JdbcConnectionFactory;
import io.opendrs.migration.service.MappingValidator;
import io.opendrs.sink.SinkConfig;
import io.opendrs.sink.exception.ApplierException;
import io.opendrs.sink.naming.DefaultColumnNamingStrategy;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.kafka.connect.source.SourceRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Builds {@link DebeziumEngine} via {@code DebeziumEngine.create} (AsyncEmbeddedEngine in 3.6).
 */
@Component
public class DebeziumCdcEngineFactory implements CdcEngineFactory {

    private static final Logger log = LoggerFactory.getLogger(DebeziumCdcEngineFactory.class);

    private final JdbcConnectionFactory connectionFactory;
    private final MappingValidator mappingValidator;
    private final TargetSchemaReconciler schemaReconciler;

    public DebeziumCdcEngineFactory(
            JdbcConnectionFactory connectionFactory, MappingValidator mappingValidator) {
        this.connectionFactory = connectionFactory;
        this.mappingValidator = mappingValidator;
        this.schemaReconciler = new TargetSchemaReconciler(connectionFactory);
    }

    public CdcEngine create(DebeziumEngineConfig.EngineSpec spec) {
        return create(spec, (Runnable) null);
    }

    @Override
    public CdcEngine create(DebeziumEngineConfig.EngineSpec spec, Runnable onSnapshotCompleted) {
        if (spec.target() == null) {
            return build(
                    DebeziumEngineConfig.capture(spec),
                    spec.taskId(),
                    new SnapshotCompletionConsumer(new LoggingChangeConsumer(spec.taskId()), onSnapshotCompleted),
                    null,
                    null);
        }
        SinkApplyChangeConsumer applyConsumer = applyConsumer(spec);
        return build(
                DebeziumEngineConfig.capture(spec),
                spec.taskId(),
                new SnapshotCompletionConsumer(applyConsumer, onSnapshotCompleted),
                applyConsumer,
                () -> {
                    try {
                        schemaReconciler.ensureTables(
                                spec,
                                applyConsumer.jdbcApplier(),
                                specTableNaming(spec),
                                new DefaultColumnNamingStrategy());
                    } catch (ApplierException ex) {
                        throw new IllegalStateException(
                                "Failed to ensure target schema before snapshot for task " + spec.taskId(),
                                ex);
                    }
                });
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
                new SnapshotCompletionConsumer(consumer, onSnapshotCompleted),
                null,
                null);
    }

    private SinkApplyChangeConsumer applyConsumer(DebeziumEngineConfig.EngineSpec spec) {
        SinkConfig config = SinkConfig.builder()
                .connectionProvider(new TargetConnectionProvider(connectionFactory, spec.target()))
                .tableNamingStrategy(specTableNaming(spec))
                .columnNamingStrategy(new DefaultColumnNamingStrategy())
                .applyDdlEnabled(true)
                .build();
        return new SinkApplyChangeConsumer(spec.taskId(), config);
    }

    private MappedTableNamingStrategy specTableNaming(DebeziumEngineConfig.EngineSpec spec) {
        return new MappedTableNamingStrategy(mappingValidator, spec.tableSelection());
    }

    private CdcEngine build(
            java.util.Properties props,
            long taskId,
            DebeziumEngine.ChangeConsumer<RecordChangeEvent<SourceRecord>> consumer,
            AutoCloseable resource,
            Runnable beforeRun) {
        AtomicReference<Throwable> error = new AtomicReference<>();
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
        return new DebeziumCdcEngine(engine, error, taskId, resource, beforeRun);
    }

    static final class DebeziumCdcEngine implements CdcEngine {

        private final DebeziumEngine<?> engine;
        private final AtomicReference<Throwable> error;
        private final long taskId;
        private final AutoCloseable resource;
        private final Runnable beforeRun;

        DebeziumCdcEngine(
                DebeziumEngine<?> engine,
                AtomicReference<Throwable> error,
                long taskId,
                AutoCloseable resource,
                Runnable beforeRun) {
            this.engine = engine;
            this.error = error;
            this.taskId = taskId;
            this.resource = resource;
            this.beforeRun = beforeRun;
        }

        @Override
        public void run() {
            if (beforeRun != null) {
                beforeRun.run();
            }
            engine.run();
            Throwable failure = error.get();
            if (failure != null) {
                if (failure instanceof RuntimeException runtime) {
                    throw runtime;
                }
                throw new IllegalStateException("Debezium Engine failed for task " + taskId, failure);
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
                closeQuietly(resource);
            }
        }
    }

    private static void closeQuietly(AutoCloseable resource) {
        if (resource == null) {
            return;
        }
        try {
            resource.close();
        } catch (Exception ex) {
            log.debug("Failed to close engine resource: {}", ex.getMessage());
        }
    }
}
