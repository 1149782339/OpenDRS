package io.opendrs.migration.service.deferred;

import io.opendrs.engine.common.log.MigrationLogLevel;
import io.opendrs.engine.common.log.MigrationLogListener;
import io.opendrs.engine.common.metadata.TableId;
import io.opendrs.engine.common.selection.TableSelection;
import io.opendrs.engine.core.TableSelectionExpander;
import io.opendrs.engine.jdbc.JdbcConnection;
import io.opendrs.engine.jdbc.JdbcConnectionFactory;
import io.opendrs.engine.jdbc.binlog.BinlogPosition;
import io.opendrs.engine.jdbc.deferred.DeferredObject;
import io.opendrs.engine.jdbc.deferred.DeferredObjectType;
import io.opendrs.engine.jdbc.deferred.TargetObjectCatalog;
import io.opendrs.engine.replay.mapping.MappingValidator;
import io.opendrs.migration.api.request.MigrationOptions.TargetTablePolicy;
import io.opendrs.migration.domain.ConnectionInfo;
import io.opendrs.migration.domain.DeferredGate;
import io.opendrs.migration.domain.DeferredObjectRecord;
import io.opendrs.migration.domain.DeferredObjectState;
import io.opendrs.migration.domain.TaskPipeline;
import io.opendrs.migration.mapper.DeferredObjectMapper;
import io.opendrs.migration.mapper.TaskPipelineMapper;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Deferred target objects of one pipeline: the secondary indexes, unique keys and foreign keys of
 * its target tables, dropped before the full load and rebuilt after it.
 *
 * <p>Everything here is idempotent and checks the target catalog before it acts, so the
 * coordinator can call any step again after a crash: the capture runs at most once per pipeline
 * (guarded by {@code task_pipelines.deferred_gate}), the rows are written before anything is
 * dropped, a drop of an object that is already gone and a create of an object that is already
 * there only update the row.
 *
 * <p>The coordinator owns the ordering (when each step runs); this class owns how a step talks to
 * the target and to {@code task_deferred_objects}.
 */
@Service
public class DeferredObjectService {

    private static final Logger log = LoggerFactory.getLogger(DeferredObjectService.class);

    private static final int ERROR_MAX = 1024;

    /** Objects built while incremental replay already runs. */
    public static final Set<DeferredObjectType> AFTER_REPLAY_TYPES =
            EnumSet.of(DeferredObjectType.INDEX, DeferredObjectType.UNIQUE, DeferredObjectType.FOREIGN_KEY);

    /** Objects built between the snapshot and the first replayed change. */
    public static final Set<DeferredObjectType> BEFORE_REPLAY_TYPES = EnumSet.of(DeferredObjectType.INDEX);

    private final DeferredObjectMapper objectMapper;
    private final TaskPipelineMapper pipelineMapper;
    private final MappingValidator mappingValidator;
    private final JdbcConnectionFactory connectionFactory;
    private final TableSelectionExpander expander;
    private final TransactionTemplate transactionTemplate;
    private java.util.function.Function<io.opendrs.engine.common.metadata.DatabaseType, TargetObjectCatalog> catalogs =
            TargetObjectCatalog::forType;

    public DeferredObjectService(
            DeferredObjectMapper objectMapper,
            TaskPipelineMapper pipelineMapper,
            MappingValidator mappingValidator,
            JdbcConnectionFactory connectionFactory,
            TransactionTemplate transactionTemplate) {
        this.objectMapper = objectMapper;
        this.pipelineMapper = pipelineMapper;
        this.mappingValidator = mappingValidator;
        this.connectionFactory = connectionFactory;
        this.expander = new TableSelectionExpander(connectionFactory);
        this.transactionTemplate = transactionTemplate;
    }

    /** Test seam: catalogs by target type. */
    void setCatalogs(
            java.util.function.Function<io.opendrs.engine.common.metadata.DatabaseType, TargetObjectCatalog> catalogs) {
        this.catalogs = catalogs;
    }

    private TargetObjectCatalog catalogOf(ConnectionInfo target) {
        return catalogs.apply(target.toDatabaseType());
    }

    /** Target tables the pipeline loads into: the expanded selection mapped to target names. */
    public List<TableId> targetTables(ConnectionInfo source, TableSelection selection) {
        try {
            return expander.expand(source.toJdbcConfig(), selection).stream()
                    .map(table -> mappingValidator.resolveTableId(selection, table.getSchema(), table.getTable()))
                    .distinct()
                    .toList();
        } catch (SQLException ex) {
            throw new IllegalStateException("cannot expand the table selection: " + ex.getMessage(), ex);
        }
    }

    public DeferredGate gateOf(long pipelineId) {
        TaskPipeline pipeline = pipelineMapper.findById(pipelineId);
        return pipeline == null ? DeferredGate.NONE : pipeline.getDeferredGate();
    }

    /**
     * FULL_PREPARE. Applies the target table policy, captures the objects (first call only), drops
     * whatever is still present, then truncates when the policy says so. Safe to call again until
     * the full load starts.
     *
     * @throws IllegalStateException when the policy refuses non-empty tables or a step fails;
     *     the task fails with that message and stays in FULL_PREPARE
     */
    public void prepare(
            TaskPipeline pipeline,
            ConnectionInfo target,
            List<TableId> tables,
            TargetTablePolicy policy,
            MigrationLogListener narration) {
        TargetObjectCatalog catalog = catalogOf(target);
        long pipelineId = pipeline.getId();
        try (JdbcConnection connection = connectionFactory.open(target.toJdbcConfig())) {
            if (policy == TargetTablePolicy.FAIL_IF_NOT_EMPTY) {
                List<String> nonEmpty = nonEmptyTables(connection, catalog, tables);
                if (!nonEmpty.isEmpty()) {
                    throw new IllegalStateException(nonEmpty.size() + " target table(s) already hold rows "
                            + abbreviate(nonEmpty) + "; empty them, or set the target table policy to "
                            + "TRUNCATE or APPEND");
                }
            }
            if (gateOf(pipelineId) == DeferredGate.NONE) {
                List<TableId> existing = existingTables(connection, tables);
                List<DeferredObject> captured = catalog.capture(connection, existing);
                transactionTemplate.executeWithoutResult(status -> {
                    for (DeferredObject object : captured) {
                        objectMapper.insertIgnore(DeferredObjectRecord.of(pipelineId, object));
                    }
                    pipelineMapper.compareAndSetDeferredGate(pipelineId, DeferredGate.NONE, DeferredGate.CAPTURED);
                });
                narration.emit(MigrationLogLevel.INFO, "deferred target objects captured: " + summary(captured));
            }
            dropCaptured(pipelineId, connection, catalog, narration);
            if (policy == TargetTablePolicy.TRUNCATE) {
                truncate(connection, catalog, tables, narration);
            }
        } catch (SQLException ex) {
            throw new IllegalStateException("preparing the target tables failed: " + ex.getMessage(), ex);
        }
    }

    private void dropCaptured(
            long pipelineId, JdbcConnection connection, TargetObjectCatalog catalog, MigrationLogListener narration)
            throws SQLException {
        List<DeferredObjectRecord> pending = objectMapper.findByPipelineId(pipelineId).stream()
                .filter(row -> row.getState() == DeferredObjectState.CAPTURED)
                .sorted(Comparator.comparing(DeferredObjectRecord::toObject, DeferredObject.DROP_ORDER))
                .toList();
        for (DeferredObjectRecord row : pending) {
            DeferredObject object = row.toObject();
            if (catalog.exists(connection, object)) {
                connection.execute(object.dropDdl());
                log.info("pipeline {} dropped {}", pipelineId, object);
            }
            objectMapper.updateState(row.getId(), DeferredObjectState.DROPPED, null);
        }
        if (!pending.isEmpty()) {
            narration.emit(MigrationLogLevel.INFO, pending.size() + " deferred target object(s) dropped");
        }
    }

    private void truncate(
            JdbcConnection connection, TargetObjectCatalog catalog, List<TableId> tables, MigrationLogListener narration)
            throws SQLException {
        // MySQL refuses TRUNCATE of a table that another table references unless FK checks are
        // off; PostgreSQL has no such switch and needs CASCADE, which this never uses.
        for (String sql : catalog.disableForeignKeySql()) {
            if (!sql.contains("session_replication_role")) {
                connection.execute(sql);
            }
        }
        List<TableId> existing = existingTables(connection, tables);
        for (TableId table : existing) {
            try {
                connection.execute(catalog.truncateSql(table));
            } catch (SQLException ex) {
                throw new IllegalStateException("truncate of target table " + table.getSchema() + "."
                        + table.getTable() + " failed (CASCADE is never used): " + ex.getMessage(), ex);
            }
        }
        narration.emit(MigrationLogLevel.INFO, existing.size() + " target table(s) truncated before the full load");
    }

    /** Records the snapshot end position (P1) once; a later call keeps the first one. */
    public BinlogPosition recordSnapshotEnd(long pipelineId, ConnectionInfo source) {
        TaskPipeline pipeline = pipelineMapper.findById(pipelineId);
        if (pipeline != null && pipeline.getSnapshotEndPosition() != null) {
            return BinlogPosition.fromMap(pipeline.getSnapshotEndPosition());
        }
        try (JdbcConnection connection = connectionFactory.open(source.toJdbcConfig())) {
            BinlogPosition position = BinlogPosition.read(connection);
            pipelineMapper.updateSnapshotEndPosition(pipelineId, position.toMap());
            return position;
        } catch (SQLException ex) {
            throw new IllegalStateException("reading the source binlog position failed: " + ex.getMessage(), ex);
        }
    }

    public Optional<BinlogPosition> snapshotEnd(long pipelineId) {
        TaskPipeline pipeline = pipelineMapper.findById(pipelineId);
        Map<String, Object> stored = pipeline == null ? null : pipeline.getSnapshotEndPosition();
        return stored == null ? Optional.empty() : Optional.of(BinlogPosition.fromMap(stored));
    }

    /** Result of one rebuild pass. */
    public record RebuildOutcome(int rebuilt, int failed, int remaining) {
    }

    /**
     * Creates the pipeline's objects of {@code types} that are not on the target yet, in build
     * order (indexes, unique keys, foreign keys). A failure marks that object FAILED with the
     * database's message and moves on; nothing here throws for a single object.
     *
     * @param maxAttempts FAILED objects that already failed this often are left alone (automatic
     *     retries stop; a manual retry resets the counter); 0 for no limit
     */
    public RebuildOutcome rebuild(
            long pipelineId,
            ConnectionInfo target,
            Set<DeferredObjectType> types,
            int maxAttempts,
            MigrationLogListener narration) {
        TargetObjectCatalog catalog = catalogOf(target);
        List<DeferredObjectRecord> pending = pending(pipelineId).stream()
                .filter(row -> types.contains(row.getObjectType()))
                .filter(row -> maxAttempts <= 0
                        || row.getState() != DeferredObjectState.FAILED
                        || row.getAttempts() < maxAttempts)
                .sorted(Comparator.comparing(DeferredObjectRecord::toObject, DeferredObject.BUILD_ORDER))
                .toList();
        int rebuilt = 0;
        int failed = 0;
        if (!pending.isEmpty()) {
            try (JdbcConnection connection = connectionFactory.open(target.toJdbcConfig())) {
                // Foreign keys come back without re-checking the rows (MySQL) or NOT VALID (PG):
                // replay keeps the data consistent, and a full check would lock and scan the child.
                for (String sql : catalog.disableForeignKeySql()) {
                    trySession(connection, sql);
                }
                for (List<DeferredObjectRecord> group : groups(pending, catalog)) {
                    for (boolean ok : build(connection, catalog, group, narration)) {
                        if (ok) {
                            rebuilt++;
                        } else {
                            failed++;
                        }
                    }
                }
            } catch (RuntimeException ex) {
                throw new IllegalStateException("cannot connect to the target to rebuild objects: "
                        + ex.getMessage(), ex);
            }
        }
        int remaining = pending(pipelineId).size();
        if (!pending.isEmpty()) {
            narration.emit(failed == 0 ? MigrationLogLevel.INFO : MigrationLogLevel.WARNING,
                    "deferred target objects rebuilt: " + rebuilt + " ok, " + failed + " failed, "
                            + remaining + " not rebuilt yet");
        }
        return new RebuildOutcome(rebuilt, failed, remaining);
    }

    /** Objects not REBUILT yet (DROPPED, FAILED, or CAPTURED if a drop never ran). */
    public List<DeferredObjectRecord> pending(long pipelineId) {
        return objectMapper.findByPipelineId(pipelineId).stream()
                .filter(row -> row.getState() != DeferredObjectState.REBUILT)
                .toList();
    }

    /** FAILED rows that have used up {@code maxAttempts} (automatic retries stop for them). */
    public boolean onlyExhaustedLeft(long pipelineId, int maxAttempts) {
        List<DeferredObjectRecord> pending = pending(pipelineId);
        return !pending.isEmpty() && pending.stream()
                .allMatch(row -> row.getState() == DeferredObjectState.FAILED && row.getAttempts() >= maxAttempts);
    }

    /** CAPTURED → RESTORED once nothing is left to rebuild; true when the gate is RESTORED. */
    public boolean completeIfRestored(long pipelineId) {
        if (!pending(pipelineId).isEmpty()) {
            return false;
        }
        pipelineMapper.compareAndSetDeferredGate(pipelineId, DeferredGate.CAPTURED, DeferredGate.RESTORED);
        return gateOf(pipelineId) == DeferredGate.RESTORED;
    }

    /** Refreshes planner statistics after a bulk load; best effort. */
    public void analyze(ConnectionInfo target, Collection<TableId> tables, MigrationLogListener narration) {
        TargetObjectCatalog catalog = catalogOf(target);
        int done = 0;
        try (JdbcConnection connection = connectionFactory.open(target.toJdbcConfig())) {
            for (TableId table : existingTables(connection, tables)) {
                try {
                    connection.execute(catalog.analyzeSql(table));
                    done++;
                } catch (SQLException ex) {
                    log.warn("ANALYZE of {} failed: {}", table, ex.getMessage());
                }
            }
        } catch (RuntimeException | SQLException ex) {
            log.warn("ANALYZE skipped: {}", ex.getMessage());
        }
        narration.emit(MigrationLogLevel.INFO, "statistics refreshed on " + done + " target table(s)");
    }

    public List<DeferredObjectRecord> list(long taskId) {
        return objectMapper.findByTaskId(taskId);
    }

    public int resetFailed(long taskId) {
        return objectMapper.resetFailed(taskId);
    }

    public void deleteByTaskId(long taskId) {
        objectMapper.deleteByTaskId(taskId);
    }

    /**
     * Rebuild units: MySQL builds all plain/unique indexes of one table in one ALTER (one table
     * copy instead of one per index); everything else is built alone.
     */
    static List<List<DeferredObjectRecord>> groups(List<DeferredObjectRecord> ordered, TargetObjectCatalog catalog) {
        List<List<DeferredObjectRecord>> groups = new ArrayList<>();
        List<DeferredObjectRecord> current = new ArrayList<>();
        for (DeferredObjectRecord row : ordered) {
            // Probe: whether the dialect would fold this object into a per-table ALTER at all.
            boolean mergeable = row.getObjectType() != DeferredObjectType.FOREIGN_KEY
                    && catalog.mergedCreate(List.of(row.toObject(), row.toObject())).isPresent();
            if (!current.isEmpty() && mergeable && sameUnit(current.getFirst(), row)) {
                current.add(row);
                continue;
            }
            if (!current.isEmpty()) {
                groups.add(current);
            }
            current = new ArrayList<>();
            current.add(row);
            if (!mergeable) {
                groups.add(current);
                current = new ArrayList<>();
            }
        }
        if (!current.isEmpty()) {
            groups.add(current);
        }
        return groups;
    }

    private static boolean sameUnit(DeferredObjectRecord a, DeferredObjectRecord b) {
        return a.getObjectType() == b.getObjectType()
                && a.getSchemaName().equals(b.getSchemaName())
                && a.getTableName().equals(b.getTableName());
    }

    private List<Boolean> build(
            JdbcConnection connection,
            TargetObjectCatalog catalog,
            List<DeferredObjectRecord> group,
            MigrationLogListener narration) {
        List<DeferredObjectRecord> missing = new ArrayList<>();
        List<Boolean> results = new ArrayList<>();
        for (DeferredObjectRecord row : group) {
            try {
                if (catalog.exists(connection, row.toObject())) {
                    objectMapper.updateState(row.getId(), DeferredObjectState.REBUILT, null);
                    results.add(true);
                } else {
                    missing.add(row);
                }
            } catch (SQLException ex) {
                missing.add(row);
            }
        }
        if (missing.size() > 1) {
            Optional<String> merged = catalog.mergedCreate(missing.stream().map(DeferredObjectRecord::toObject).toList());
            if (merged.isPresent()) {
                try {
                    connection.execute(merged.get());
                    for (DeferredObjectRecord row : missing) {
                        objectMapper.recordAttempt(row.getId(), DeferredObjectState.REBUILT, null);
                        results.add(true);
                    }
                    return results;
                } catch (SQLException ex) {
                    // One bad object fails the whole ALTER; build them one by one to find it.
                    log.warn("merged rebuild on {}.{} failed, retrying one by one: {}",
                            missing.getFirst().getSchemaName(), missing.getFirst().getTableName(), ex.getMessage());
                }
            }
        }
        for (DeferredObjectRecord row : missing) {
            results.add(buildOne(connection, row, narration));
        }
        return results;
    }

    private boolean buildOne(JdbcConnection connection, DeferredObjectRecord row, MigrationLogListener narration) {
        DeferredObject object = row.toObject();
        try {
            connection.execute(object.createDdl());
        } catch (SQLException ex) {
            String error = truncateError(ex.getMessage());
            objectMapper.recordAttempt(row.getId(), DeferredObjectState.FAILED, error);
            narration.emit(MigrationLogLevel.WARNING, "rebuild of " + object + " failed: " + error);
            return false;
        }
        String warning = null;
        if (object.postDdl() != null) {
            try {
                connection.execute(object.postDdl());
            } catch (SQLException ex) {
                // The constraint exists and is enforced for new rows; only the old rows are unchecked.
                warning = truncateError("created NOT VALID, validation failed: " + ex.getMessage());
                narration.emit(MigrationLogLevel.WARNING, object + " " + warning);
            }
        }
        objectMapper.recordAttempt(row.getId(), DeferredObjectState.REBUILT, warning);
        log.info("rebuilt {}", object);
        return true;
    }

    private static void trySession(JdbcConnection connection, String sql) {
        try {
            connection.execute(sql);
        } catch (SQLException ex) {
            log.warn("session statement \"{}\" refused: {}", sql, ex.getMessage());
        }
    }

    private static List<String> nonEmptyTables(
            JdbcConnection connection, TargetObjectCatalog catalog, List<TableId> tables) throws SQLException {
        List<String> nonEmpty = new ArrayList<>();
        for (TableId table : existingTables(connection, tables)) {
            if (connection.queryOne(catalog.probeRowSql(table), rs -> 1) != null) {
                nonEmpty.add(table.getSchema() + "." + table.getTable());
            }
        }
        return nonEmpty;
    }

    private static List<TableId> existingTables(JdbcConnection connection, Collection<TableId> tables)
            throws SQLException {
        List<TableId> existing = new ArrayList<>();
        for (TableId table : tables) {
            if (connection.tableExists(table.getSchema(), table.getTable())) {
                existing.add(table);
            }
        }
        return existing;
    }

    private static String summary(List<DeferredObject> objects) {
        long indexes = objects.stream().filter(o -> o.type() == DeferredObjectType.INDEX).count();
        long uniques = objects.stream().filter(o -> o.type() == DeferredObjectType.UNIQUE).count();
        long fks = objects.stream().filter(o -> o.type() == DeferredObjectType.FOREIGN_KEY).count();
        return indexes + " index(es), " + uniques + " unique key(s), " + fks + " foreign key(s)";
    }

    private static String abbreviate(List<String> names) {
        List<String> shown = names.stream().sorted().limit(10).toList();
        return shown + (names.size() > shown.size() ? " and " + (names.size() - shown.size()) + " more" : "");
    }

    private static String truncateError(String message) {
        String text = message == null ? "unknown error" : message;
        return text.length() > ERROR_MAX ? text.substring(0, ERROR_MAX) : text;
    }
}
