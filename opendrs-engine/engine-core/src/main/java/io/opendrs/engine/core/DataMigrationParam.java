package io.opendrs.engine.core;

import io.opendrs.common.log.MigrationLogListener;
import io.opendrs.common.progress.MigrationLagListener;
import io.opendrs.engine.replay.GtidLoopConfig;
import io.opendrs.engine.replay.mapping.TableSelection;
import io.opendrs.engine.replay.metrics.ReplayDirection;
import io.opendrs.jdbc.JdbcConnectionConfig;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * One CDC run: snapshot the selected tables, then stream the binlog into the target.
 *
 * <p>The target schema must already exist — this job never creates tables. Callers run
 * {@link MigrationEngine#migrateSchema(SchemaMigrationParam)} first; when a target table is
 * missing the run fails immediately with a message that says so, instead of failing halfway
 * through the snapshot on the first insert.
 *
 * <p>{@code null} selection means the whole source.
 *
 * @param logListener sees what only the capture path knows: the expanded table count, the snapshot
 *     turning into a stream, and a rejected CDC batch; called on engine threads
 * @param lagListener told after each committed batch when the newest change happened in the
 *     source, and by heartbeats when the source is quiet; called on the Debezium thread
 */
public record DataMigrationParam(
        long taskId,
        JdbcConnectionConfig source,
        JdbcConnectionConfig target,
        TableSelection selection,
        Long databaseServerId,
        Consumer<DataMigrationTaskPhase> phaseListener,
        MigrationLogListener logListener,
        MigrationLagListener lagListener,
        /**
         * Runtime namespace of this run: engine name, topic prefix and the offset/schema-history
         * rows all key off it. Null lets the engine fall back to the task id (tests, precheck-free
         * callers without a persisted pipeline).
         */
        Long pipelineId,
        /** loop-prevention wiring; null keeps single-direction behaviour. */
        GtidLoopConfig gtidLoop,
        /**
         * Which physical direction this pipeline carries; tags the sink metrics so the
         * monitor can split a bidirectional task's two pipelines. Null defaults to forward.
         */
        ReplayDirection direction,
        /**
         * Worker count of the incremental transaction applier; the service wires
         * {@code options.incremental.replayConcurrency} here. Null lets the sink fall back
         * to its own default.
         */
        Integer txApplierConcurrency,
        /**
         * Worker count of the full-load table applier; the service wires
         * {@code options.full.importConcurrency} here. Null lets the sink fall back to its
         * own default.
         */
        Integer tableApplierConcurrency,
        /**
         * Thread count of the Debezium snapshot reader; the service wires
         * {@code options.full.exportConcurrency}. Null keeps the connector default (serial
         * snapshot).
         */
        Integer snapshotThreads,
        /**
         * Debezium {@code snapshot.max.threads.multiplier}: snapshot chunks per thread, so
         * 实际分片数 ≈ 并发线程数 × 分片倍率. The service wires
         * {@code options.full.chunkMultiplier}. Null keeps the connector default.
         */
        Integer snapshotThreadsMultiplier)
        implements MigrationParam {

    public DataMigrationParam {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(target, "target");
        logListener = logListener == null ? MigrationLogListener.NONE : logListener;
        lagListener = lagListener == null ? MigrationLagListener.NONE : lagListener;
        direction = direction == null ? ReplayDirection.FORWARD : direction;
    }

    /** Compact factory for the classic single-direction run. */
    public static DataMigrationParam of(
            long taskId,
            JdbcConnectionConfig source,
            JdbcConnectionConfig target,
            TableSelection selection,
            Long databaseServerId,
            Consumer<DataMigrationTaskPhase> phaseListener,
            MigrationLogListener logListener,
            MigrationLagListener lagListener) {
        return new DataMigrationParam(
                taskId, source, target, selection, databaseServerId,
                phaseListener, logListener, lagListener, null, null, ReplayDirection.FORWARD,
                null, null, null, null);
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {

        private long taskId;
        private JdbcConnectionConfig source;
        private JdbcConnectionConfig target;
        private TableSelection selection;
        private Long databaseServerId;
        private Consumer<DataMigrationTaskPhase> phaseListener;
        private MigrationLogListener logListener;
        private MigrationLagListener lagListener;
        private Long pipelineId;
        private GtidLoopConfig gtidLoop;
        private ReplayDirection direction;
        private Integer txApplierConcurrency;
        private Integer tableApplierConcurrency;
        private Integer snapshotThreads;
        private Integer snapshotThreadsMultiplier;

        private Builder() {
        }

        public Builder taskId(long taskId) {
            this.taskId = taskId;
            return this;
        }

        public Builder source(JdbcConnectionConfig source) {
            this.source = source;
            return this;
        }

        public Builder target(JdbcConnectionConfig target) {
            this.target = target;
            return this;
        }

        public Builder selection(TableSelection selection) {
            this.selection = selection;
            return this;
        }

        /**
         * Debezium's {@code database.server.id}. {@code null} lets the engine derive it from the
         * task id, which is unique by construction and therefore collision-free within one
         * deployment.
         */
        public Builder databaseServerId(Long databaseServerId) {
            this.databaseServerId = databaseServerId;
            return this;
        }

        /** Notified when the run enters a phase; called on the engine worker thread. */
        public Builder phaseListener(Consumer<DataMigrationTaskPhase> phaseListener) {
            this.phaseListener = phaseListener;
            return this;
        }

        /** Called on engine threads, including the Debezium change consumer. Must not block. */
        public Builder logListener(MigrationLogListener logListener) {
            this.logListener = logListener;
            return this;
        }

        /**
         * Fed after each committed batch and by heartbeats. Called on the Debezium thread, so the
         * implementation must be thread-safe and must not block.
         */
        public Builder lagListener(MigrationLagListener lagListener) {
            this.lagListener = lagListener;
            return this;
        }

        /**
         * Pipeline whose namespace this run executes under; null falls back to the task id
         * (callers without a persisted pipeline row).
         */
        public Builder pipelineId(Long pipelineId) {
            this.pipelineId = pipelineId;
            return this;
        }

        /** Loop-prevention wiring for this pipeline; null keeps single-direction behaviour. */
        public Builder gtidLoop(GtidLoopConfig gtidLoop) {
            this.gtidLoop = gtidLoop;
            return this;
        }

        /**
         * Physical direction of this pipeline; null defaults to forward. Bidirectional callers
         * set REVERSE on the pipeline whose source endpoint is the task's target.
         */
        public Builder direction(ReplayDirection direction) {
            this.direction = direction;
            return this;
        }

        /** Incremental applier worker count; null keeps the sink default. */
        public Builder txApplierConcurrency(Integer txApplierConcurrency) {
            this.txApplierConcurrency = txApplierConcurrency;
            return this;
        }

        /** Full-load applier worker count; null keeps the sink default. */
        public Builder tableApplierConcurrency(Integer tableApplierConcurrency) {
            this.tableApplierConcurrency = tableApplierConcurrency;
            return this;
        }

        /** Debezium snapshot reader thread count; null keeps the connector default. */
        public Builder snapshotThreads(Integer snapshotThreads) {
            this.snapshotThreads = snapshotThreads;
            return this;
        }

        /** Debezium {@code snapshot.max.threads.multiplier}; null keeps the connector default. */
        public Builder snapshotThreadsMultiplier(Integer snapshotThreadsMultiplier) {
            this.snapshotThreadsMultiplier = snapshotThreadsMultiplier;
            return this;
        }

        public DataMigrationParam build() {
            return new DataMigrationParam(
                    taskId,
                    source,
                    target,
                    selection,
                    databaseServerId,
                    phaseListener,
                    logListener,
                    lagListener,
                    pipelineId,
                    gtidLoop,
                    direction,
                    txApplierConcurrency,
                    tableApplierConcurrency,
                    snapshotThreads,
                    snapshotThreadsMultiplier);
        }
    }
}
