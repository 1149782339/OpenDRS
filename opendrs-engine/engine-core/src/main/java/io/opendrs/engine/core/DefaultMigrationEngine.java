package io.opendrs.engine.core;

import io.opendrs.common.log.MigrationLogLevel;
import io.opendrs.common.log.MigrationLogListener;
import io.opendrs.common.metadata.DatabaseType;
import io.opendrs.engine.capture.CdcEngine;
import io.opendrs.engine.capture.DebeziumCdcEngineFactory;
import io.opendrs.engine.capture.DebeziumEngineConfig;
import io.opendrs.engine.precheck.PlanBuilder;
import io.opendrs.engine.precheck.PrecheckContext;
import io.opendrs.engine.precheck.PrecheckException;
import io.opendrs.engine.precheck.PrecheckPlan;
import io.opendrs.engine.precheck.PrecheckProfile;
import io.opendrs.engine.precheck.PrecheckProfiles;
import io.opendrs.engine.precheck.PrecheckResults;
import io.opendrs.engine.precheck.PrecheckRunner;
import io.opendrs.engine.precheck.fact.FactStore;
import io.opendrs.engine.precheck.profile.MysqlBidirectionalProfile;
import io.opendrs.engine.precheck.profile.MysqlToPgProfile;
import io.opendrs.engine.replay.exception.ApplierException;
import io.opendrs.engine.replay.mapping.MappingValidator;
import io.opendrs.engine.replay.mapping.TableSelection;
import io.opendrs.engine.replay.metrics.ReplayMetrics;
import io.opendrs.jdbc.JdbcConnectionFactory;
import io.opendrs.jdbc.JdbcException;
import io.opendrs.jdbc.dialect.MysqlDialect;
import io.opendrs.jdbc.dialect.PostgresDialect;
import io.opendrs.jdbc.metadata.Table;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Assembles the engine object graph with plain constructors. The service provides the mechanism
 * collaborators ({@link JdbcConnectionFactory}, dialects, metadata {@link DataSource} — backed by
 * its own beans); everything else is engine-owned.
 *
 * <p>Each operation hands its work to an internal cached pool and returns the handle immediately.
 * That pool is deliberately separate from the service's job executor: a job thread parks on
 * {@code await()}, so sharing a pool would let one task starve the engine thread it is waiting on.
 */
public class DefaultMigrationEngine implements MigrationEngine {

    private static final Logger log = LoggerFactory.getLogger(DefaultMigrationEngine.class);

    /** First id handed out when the caller does not pin Debezium's {@code database.server.id}. */
    private static final long SERVER_ID_BASE = 100_000L;

    /** MySQL {@code server_id} is an unsigned 32-bit value. */
    private static final long SERVER_ID_MAX = 0xFFFFFFFFL;

    private final JdbcConnectionFactory factory;
    private final TableSelectionExpander expander;
    private final MappingValidator mappingValidator;
    private final PrecheckProfiles precheckProfiles;
    private final PlanBuilder mysqlToPgPlanBuilder;
    private final PlanBuilder mysqlBidirectionalPlanBuilder;
    private final DebeziumCdcEngineFactory cdcEngineFactory;
    private final SchemaMigrator schemaMigrator;
    private final ReplayMetrics replayMetrics;
    private final ExecutorService executor = Executors.newCachedThreadPool(new EngineThreadFactory());

    public DefaultMigrationEngine(
            JdbcConnectionFactory factory,
            MysqlDialect mysqlDialect,
            PostgresDialect postgresDialect,
            DataSource metadataDataSource) {
        this(factory, mysqlDialect, postgresDialect, metadataDataSource, ReplayMetrics.NONE);
    }

    public DefaultMigrationEngine(
            JdbcConnectionFactory factory,
            MysqlDialect mysqlDialect,
            PostgresDialect postgresDialect,
            DataSource metadataDataSource,
            ReplayMetrics replayMetrics) {
        this.factory = factory;
        this.expander = new TableSelectionExpander(factory);
        this.mappingValidator = new MappingValidator();
        this.schemaMigrator = new SchemaMigrator(factory, mappingValidator);
        this.precheckProfiles = new PrecheckProfiles(List.of(new MysqlToPgProfile(), new MysqlBidirectionalProfile()));
        this.mysqlToPgPlanBuilder = new PlanBuilder(MysqlToPgProfile.registry());
        this.mysqlBidirectionalPlanBuilder = new PlanBuilder(MysqlBidirectionalProfile.registry());
        this.cdcEngineFactory = new DebeziumCdcEngineFactory(factory, mappingValidator, metadataDataSource);
        this.replayMetrics = replayMetrics;
    }

    @Override
    public boolean supportsPrecheck(DatabaseType source, DatabaseType target) {
        return precheckProfiles.supports(source, target);
    }

    @Override
    public SchemaMigrationTask migrateSchema(SchemaMigrationParam param) {
        TableSelection selection = selection(param.selection());
        MigrationLogListener listener = param.logListener();
        DefaultSchemaMigrationTask task = new DefaultSchemaMigrationTask(() -> {
            List<Table> tables = expand(param.source(), selection);
            if (tables.isEmpty()) {
                throw new IllegalStateException("No tables matched the selection for schema migration: "
                        + describeSelection(selection));
            }
            log.info("schema migration starting for {} tables", tables.size());
            listener.emit(
                    MigrationLogLevel.INFO, "schema migration starting for " + tables.size() + " table(s)");
            return callUnchecked(
                    () -> schemaMigrator.migrate(param.source(), param.target(), tables, selection, listener));
        });
        task.start(executor);
        return task;
    }

    @Override
    public DataMigrationTask migrateData(DataMigrationParam param) {
        TableSelection selection = selection(param.selection());
        DefaultDataMigrationTask task = new DefaultDataMigrationTask(
                param.taskId(),
                onSnapshot -> startCapture(param, selection, onSnapshot),
                param.phaseListener());
        task.start(executor);
        return task;
    }

    @Override
    public PrecheckTask precheck(PrecheckParam param) {
        TableSelection selection = selection(param.selection());
        DefaultPrecheckTask task = new DefaultPrecheckTask(() -> runPrecheck(param, selection));
        task.start(executor);
        return task;
    }

    /** Releases the worker pool. Called by the service on shutdown. */
    public void close() {
        executor.shutdownNow();
    }

    private CdcEngine startCapture(DataMigrationParam param, TableSelection selection, Runnable onSnapshot) {
        MigrationLogListener listener = param.logListener();
        List<Table> tables = expand(param.source(), selection);
        if (tables.isEmpty()) {
            throw new IllegalStateException("No tables matched the selection for task " + param.taskId()
                    + ": " + describeSelection(selection));
        }
        listener.emit(
                MigrationLogLevel.INFO, "selection matched " + tables.size() + " table(s), starting capture");
        List<Table> missing = callUnchecked(
                () -> schemaMigrator.missingTargets(param.target(), tables, selection));
        if (!missing.isEmpty()) {
            throw new IllegalStateException("Target tables are missing for task " + param.taskId()
                    + ", run schema migration first: " + missing);
        }
        DebeziumEngineConfig.EngineSpec spec = new DebeziumEngineConfig.EngineSpec(
                param.taskId(),
                param.pipelineId() == null ? param.taskId() : param.pipelineId(),
                param.source(),
                tables,
                serverId(param),
                param.target(),
                selection,
                replayMetrics,
                param.gtidLoop(),
                param.direction(),
                param.txApplierConcurrency(),
                param.tableApplierConcurrency(),
                param.snapshotThreads(),
                param.snapshotThreadsMultiplier(),
                param.snapshotStrategy());
        return cdcEngineFactory.create(spec, onSnapshot, listener, param.lagListener());
    }

    /**
     * Builds the checklist for this direction, then runs it.
     *
     * <p>The table lists are expanded here because the engine owns the selection and the mapping;
     * the precheck module only sees concrete {@link Table} rows. A failed source expansion is not
     * fatal: the selection is taken literally, {@code tablesKnown} goes false, and the table-level
     * checks report themselves blocked instead of inspecting an empty list.
     *
     * <p>Everything else - which checks exist, in what order, what blocks - lives in the profile.
     */
    private PrecheckResults runPrecheck(PrecheckParam param, TableSelection selection) {
        PrecheckProfile profile = precheckProfiles
                .of(param.source().getType(), param.target().getType())
                .orElseThrow(() -> new PrecheckException("No precheck profile for "
                        + param.source().getType() + " → " + param.target().getType()));

        List<Table> sourceTables;
        boolean tablesKnown = true;
        try {
            sourceTables = expander.expand(param.source(), selection);
        } catch (JdbcException ex) {
            // The connection check reports this failure; the other rows depend on it.
            sourceTables = expander.expandExplicit(selection);
            tablesKnown = false;
        }
        List<Table> targetTables = tablesKnown
                ? mappingValidator.mapTargets(selection, sourceTables)
                : List.of();

        PrecheckContext context = new PrecheckContext(
                param.source(), param.target(), sourceTables, targetTables, tablesKnown,
                param.bidirectional());
        // Keyed by the resolved profile, not by the topology flag: the MySQL → MySQL pair always
        // resolves to the bidirectional profile (it is the only MySQL → MySQL checklist), and a
        // one-way topology only narrows which of its checks are bound — never the registry.
        // Picking the registry from the flag is how a one-way MySQL → MySQL task once died at
        // assembly time with "Unknown precheck rule: target.log_bin".
        PlanBuilder planBuilder = MysqlBidirectionalProfile.ID.equals(profile.id())
                ? mysqlBidirectionalPlanBuilder
                : mysqlToPgPlanBuilder;
        PrecheckPlan plan = planBuilder.build(profile, context);
        try (FactStore facts = new FactStore(context, factory)) {
            return new PrecheckRunner(plan, context, facts, param.progressListener()).run();
        }
    }

    private List<Table> expand(io.opendrs.jdbc.JdbcConnectionConfig source, TableSelection selection) {
        return expander.expand(source, selection);
    }

    /**
     * Debezium needs a client server id that is unique among everything connected to the source.
     * Deriving it from the task id makes it unique by construction inside one deployment; pinning
     * it through {@link DataMigrationParam#databaseServerId()} stays possible for operations that
     * need a fixed range.
     */
    private static long serverId(DataMigrationParam param) {
        if (param.databaseServerId() != null) {
            return param.databaseServerId();
        }
        long id = SERVER_ID_BASE + param.taskId();
        if (id > SERVER_ID_MAX) {
            throw new IllegalStateException("Debezium server id space exhausted: " + id);
        }
        return id;
    }

    /**
     * {@code null} means "no filter" — the whole source. This is a supported mode, not an error:
     * it is how a task follows schemas and tables created after it started.
     */
    private static TableSelection selection(TableSelection selection) {
        return selection == null ? TableSelection.all() : selection;
    }

    private static String describeSelection(TableSelection selection) {
        if (selection.isAll()) {
            return "all schemas";
        }
        if (selection.isSchemaLevel()) {
            return "schemas " + selection.schemas();
        }
        return "tables " + selection.tables();
    }

    /** Adapts the applier's checked exception to the runtime one the task handles carry. */
    private static <T> T callUnchecked(CheckedCall<T> call) {
        try {
            return call.get();
        } catch (ApplierException ex) {
            throw new IllegalStateException(ex.getMessage(), ex);
        }
    }

    @FunctionalInterface
    private interface CheckedCall<T> {
        T get() throws ApplierException;
    }

    private static final class EngineThreadFactory implements java.util.concurrent.ThreadFactory {

        private final AtomicInteger sequence = new AtomicInteger();

        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "opendrs-engine-" + sequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        }
    }
}
