package io.opendrs.debezium;

import io.debezium.engine.DebeziumEngine;
import io.debezium.engine.RecordChangeEvent;
import io.debezium.engine.format.ChangeEventFormat;
import io.debezium.engine.spi.OffsetCommitPolicy;
import io.debezium.embedded.Connect;
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

    @Override
    public CdcEngine createSchemaSnapshot(DebeziumEngineConfig.EngineSpec spec) {
        return build(
                DebeziumEngineConfig.schemaSnapshot(spec),
                spec.taskId(),
                true,
                new SchemaSnapshotStopConsumer(new LoggingChangeConsumer(spec.taskId())));
    }

    @Override
    public CdcEngine createIncremental(DebeziumEngineConfig.EngineSpec spec) {
        return createIncremental(spec, new LoggingChangeConsumer(spec.taskId()));
    }

    /**
     * Same INCREMENTAL Engine as production, with a caller-supplied consumer. Tests use this to
     * record {@link SourceRecord}s; production still goes through {@link LoggingChangeConsumer}.
     */
    public CdcEngine createIncremental(
            DebeziumEngineConfig.EngineSpec spec,
            DebeziumEngine.ChangeConsumer<RecordChangeEvent<SourceRecord>> consumer) {
        return build(DebeziumEngineConfig.incremental(spec), spec.taskId(), false, consumer);
    }

    private CdcEngine build(
            java.util.Properties props,
            long taskId,
            boolean alwaysCommit,
            DebeziumEngine.ChangeConsumer<RecordChangeEvent<SourceRecord>> consumer) {
        AtomicReference<Throwable> error = new AtomicReference<>();
        DebeziumEngine.Builder<RecordChangeEvent<SourceRecord>> builder = DebeziumEngine
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
                });
        if (alwaysCommit) {
            builder.using(OffsetCommitPolicy.always());
        }
        DebeziumEngine<RecordChangeEvent<SourceRecord>> engine = builder.build();
        return new DebeziumCdcEngine(engine, error, taskId);
    }

    static final class DebeziumCdcEngine implements CdcEngine {

        private final DebeziumEngine<?> engine;
        private final AtomicReference<Throwable> error;
        private final long taskId;

        DebeziumCdcEngine(DebeziumEngine<?> engine, AtomicReference<Throwable> error, long taskId) {
            this.engine = engine;
            this.error = error;
            this.taskId = taskId;
        }

        @Override
        public void run() {
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
            }
        }
    }
}
