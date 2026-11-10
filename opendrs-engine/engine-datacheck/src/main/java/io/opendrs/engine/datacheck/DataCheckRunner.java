package io.opendrs.engine.datacheck;

import io.opendrs.engine.common.log.MigrationLogLevel;
import io.opendrs.engine.common.metadata.DatabaseType;
import io.opendrs.engine.common.metadata.TableDefinition;
import io.opendrs.engine.common.metadata.TableId;
import io.opendrs.engine.datacheck.type.LogicalValue;
import io.opendrs.engine.datacheck.type.handler.TypeHandler;
import io.opendrs.engine.datacheck.type.handler.TypeHandlerFactory;
import io.opendrs.engine.datacheck.utils.DiffResult;
import io.opendrs.engine.datacheck.utils.HashBuckets;
import io.opendrs.engine.datacheck.utils.HashUtil;
import io.opendrs.engine.jdbc.JdbcConnection;
import io.opendrs.engine.jdbc.JdbcConnectionFactory;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

/**
 * Runs the data check table by table: both sides are streamed, each row is serialized into
 * canonical text and hashed into {@link HashBuckets}, then the two bucket sets are diffed.
 * Inconsistent primary keys are written to the {@link InconsistentRowStore}; matching ones are
 * removed from it (so a prior inconsistent mark that is consistent again is cleared).
 *
 * <p>A table is rejected before any row is read when the metadata cannot support a comparison
 * (table missing, different column counts, no primary key); that is cheaper and gives a much
 * clearer message than thousands of row mismatches caused by an incompatible layout.
 */
public final class DataCheckRunner {

    static final int BUCKET_COUNT = DataCheckRows.BUCKET_COUNT;

    private final JdbcConnectionFactory factory;

    public DataCheckRunner(JdbcConnectionFactory factory) {
        this.factory = factory;
    }

    public void run(
            DataCheckParam param, InconsistentRowStore store, BooleanSupplier stopRequested) {
        checkTable(param, store, param.sourceTable(), param.targetTable(), stopRequested);
    }

    private void checkTable(
            DataCheckParam param,
            InconsistentRowStore store,
            TableId sourceTable,
            TableId targetTable,
            BooleanSupplier stopRequested) {
        try (JdbcConnection source = factory.open(param.source());
                JdbcConnection target = factory.open(param.target())) {
            TableDefinition sourceDefinition = source.readTable(sourceTable);
            TableDefinition targetDefinition = target.readTable(targetTable);
            if (sourceDefinition == null) {
                warn(param, sourceTable, "source table not found");
                return;
            }
            if (targetDefinition == null) {
                warn(param, sourceTable, "target table not found");
                return;
            }

            int sourceColumns = sourceDefinition.getColumnDefinitions().size();
            int targetColumns = targetDefinition.getColumnDefinitions().size();
            if (sourceColumns != targetColumns) {
                warn(param, sourceTable,
                        "column count mismatch: source=" + sourceColumns + ", target=" + targetColumns);
                return;
            }
            if (sourceColumns == 0) {
                warn(param, sourceTable, "source table has no columns");
                return;
            }
            if (sourceDefinition.getPrimaryKeyColumNames().isEmpty()) {
                warn(param, sourceTable, "source table has no primary key");
                return;
            }
            if (targetDefinition.getPrimaryKeyColumNames().isEmpty()) {
                warn(param, sourceTable, "target table has no primary key");
                return;
            }
            if (sourceDefinition.getPrimaryKeyColumNames().size()
                    != targetDefinition.getPrimaryKeyColumNames().size()) {
                warn(param, sourceTable,
                        "primary key count mismatch: source="
                                + sourceDefinition.getPrimaryKeyColumNames().size()
                                + ", target=" + targetDefinition.getPrimaryKeyColumNames().size());
                return;
            }

            String sourceSql = DataCheckRows.selectAllOrderByPkSql(sourceTable, sourceDefinition, source);
            String targetSql = DataCheckRows.selectAllOrderByPkSql(targetTable, targetDefinition, target);
            compareRows(param, store, source, target, sourceTable, targetTable,
                    sourceDefinition, targetDefinition, sourceSql, targetSql, stopRequested);
        } catch (SQLException ex) {
            param.logListener().emit(MigrationLogLevel.ERROR,
                    "data check failed for " + sourceTable + ": " + ex.getMessage());
        } catch (RuntimeException ex) {
            param.logListener().emit(MigrationLogLevel.ERROR,
                    "data check failed for " + sourceTable + ": " + ex.getMessage());
        }
    }

    private static void compareRows(
            DataCheckParam param,
            InconsistentRowStore store,
            JdbcConnection source,
            JdbcConnection target,
            TableId sourceTable,
            TableId targetTable,
            TableDefinition sourceDefinition,
            TableDefinition targetDefinition,
            String sourceSql,
            String targetSql,
            BooleanSupplier stopRequested) throws SQLException {
        TypeHandler sourceHandler = TypeHandlerFactory.of(param.source().getType());
        TypeHandler targetHandler = TypeHandlerFactory.of(param.target().getType());

        java.sql.Connection sourceRaw = source.connection();
        java.sql.Connection targetRaw = target.connection();
        // PostgreSQL only uses a server-side cursor (and therefore a bounded fetch size) when the
        // connection is not in autocommit mode.
        if (param.source().getType() == DatabaseType.POSTGRESQL) {
            sourceRaw.setAutoCommit(false);
        }
        if (param.target().getType() == DatabaseType.POSTGRESQL) {
            targetRaw.setAutoCommit(false);
        }

        int[] sourcePkIndexes = DataCheckRows.primaryKeyIndexes(sourceDefinition);
        int[] targetPkIndexes = DataCheckRows.primaryKeyIndexes(targetDefinition);

        HashBuckets sourceBuckets = new HashBuckets(DataCheckRows.BUCKET_COUNT);
        HashBuckets targetBuckets = new HashBuckets(DataCheckRows.BUCKET_COUNT);
        List<String> sourcePks = new ArrayList<>();
        StringBuilder rowBuf = new StringBuilder();

        long sourceRows = 0;
        long targetRows = 0;

        try (PreparedStatement sourceStatement =
                        DataCheckRows.streamingStatement(sourceRaw, sourceSql, param.source().getType());
                PreparedStatement targetStatement =
                        DataCheckRows.streamingStatement(targetRaw, targetSql, param.target().getType());
                ResultSet sourceResult = sourceStatement.executeQuery();
                ResultSet targetResult = targetStatement.executeQuery()) {

            ResultSetMetaData sourceMeta = sourceResult.getMetaData();
            ResultSetMetaData targetMeta = targetResult.getMetaData();

            while (sourceResult.next()) {
                if (stopRequested.getAsBoolean()) {
                    break;
                }
                sourceRows++;
                List<LogicalValue> values = sourceHandler.getValues(sourceResult, sourceMeta);
                String pk = DataCheckRows.primaryKey(sourceResult, sourcePkIndexes);
                long pkHash = HashUtil.getHash(pk);
                sourceBuckets.add(pk, pkHash, DataCheckRows.rowHash(values, rowBuf));
                sourcePks.add(pk);
            }
            while (targetResult.next()) {
                if (stopRequested.getAsBoolean()) {
                    break;
                }
                targetRows++;
                List<LogicalValue> values = targetHandler.getValues(targetResult, targetMeta);
                String pk = DataCheckRows.primaryKey(targetResult, targetPkIndexes);
                long pkHash = HashUtil.getHash(pk);
                targetBuckets.add(pk, pkHash, DataCheckRows.rowHash(values, rowBuf));
            }
        }

        DiffResult diff = sourceBuckets.diff(targetBuckets);
        DataCheckRows.applyDiff(store, sourceTable, diff, sourcePks);

        long sourceOnlyRows = diff.left().size();
        long targetOnlyRows = diff.right().size();
        long mismatchedRows = diff.both().size();
        long matchedRows = Math.max(0L, sourceRows - sourceOnlyRows - mismatchedRows);
        param.logListener().emit(MigrationLogLevel.INFO,
                "data check " + sourceTable + " -> " + targetTable
                        + ": source=" + sourceRows + ", target=" + targetRows
                        + ", matched=" + matchedRows + ", mismatched=" + mismatchedRows
                        + ", sourceOnly=" + sourceOnlyRows + ", targetOnly=" + targetOnlyRows);
    }

    /** @see DataCheckRows#applyDiff */
    static void applyDiff(
            InconsistentRowStore store,
            TableId table,
            DiffResult diff,
            List<String> sourcePks) {
        DataCheckRows.applyDiff(store, table, diff, sourcePks);
    }

    private static void warn(DataCheckParam param, TableId table, String message) {
        param.logListener().emit(MigrationLogLevel.WARNING,
                "data check skipped for " + table + ": " + message);
    }
}
