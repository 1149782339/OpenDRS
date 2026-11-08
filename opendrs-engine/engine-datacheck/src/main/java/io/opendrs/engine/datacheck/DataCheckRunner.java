package io.opendrs.engine.datacheck;

import io.opendrs.engine.common.log.MigrationLogLevel;
import io.opendrs.engine.common.metadata.ColumnDefinition;
import io.opendrs.engine.common.metadata.DatabaseType;
import io.opendrs.engine.common.metadata.TableDefinition;
import io.opendrs.engine.common.metadata.TableId;
import io.opendrs.engine.datacheck.type.LogicalValue;
import io.opendrs.engine.datacheck.type.handler.TypeHandler;
import io.opendrs.engine.datacheck.type.handler.TypeHandlerFactory;
import io.opendrs.engine.datacheck.type.serialize.SerializeConfiguration;
import io.opendrs.engine.datacheck.type.serialize.SerializeContext;
import io.opendrs.engine.datacheck.type.serialize.Serializer;
import io.opendrs.engine.datacheck.type.serialize.SerializerFactory;
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
 * Runs the data check table by table: both sides are read in primary-key order, every row is
 * serialized into text, and the two row hashes are compared. Inconsistent primary keys are written
 * to the {@link InconsistentRowStore}; consistent ones are removed from it.
 *
 * <p>A table is rejected before any row is read when the metadata cannot support a comparison
 * (table missing, different column counts, no primary key); that is cheaper and gives a much
 * clearer message than thousands of row mismatches caused by an incompatible layout.
 */
public final class DataCheckRunner {

    /** Column boundary marker: without it ["a","bc"] and ["ab","c"] would hash the same. */
    private static final char COLUMN_SEPARATOR = '\u0000';

    /** Flush the in-memory key buffers to the store every this many rows. */
    private static final int FLUSH_ROWS = 1_000;

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

            String sourceSql = selectSql(sourceTable, sourceDefinition, source);
            String targetSql = selectSql(targetTable, targetDefinition, target);
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
        Serializer serializer = SerializerFactory.of(new SerializeConfiguration(
                sourceDefinition, targetDefinition, param.includeFloat(),
                param.source().getType(), param.target().getType()));
        SerializeContext context = new SerializeContext();

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

        int[] sourcePkIndexes = primaryKeyIndexes(sourceDefinition);
        int[] targetPkIndexes = primaryKeyIndexes(targetDefinition);
        int columnCount = sourceDefinition.getColumnDefinitions().size();

        List<java.lang.String> consistent = new ArrayList<>();
        List<java.lang.String> different = new ArrayList<>();
        List<java.lang.String> sourceOnly = new ArrayList<>();
        List<java.lang.String> targetOnly = new ArrayList<>();

        long sourceRows = 0;
        long targetRows = 0;
        long matchedRows = 0;
        long mismatchedRows = 0;
        long sourceOnlyRows = 0;
        long targetOnlyRows = 0;

        try (PreparedStatement sourceStatement =
                        streamingStatement(sourceRaw, sourceSql, param.source().getType());
                PreparedStatement targetStatement =
                        streamingStatement(targetRaw, targetSql, param.target().getType());
                ResultSet sourceResult = sourceStatement.executeQuery();
                ResultSet targetResult = targetStatement.executeQuery()) {

            ResultSetMetaData sourceMeta = sourceResult.getMetaData();
            ResultSetMetaData targetMeta = targetResult.getMetaData();

            boolean hasSource = sourceResult.next();
            boolean hasTarget = targetResult.next();
            while (hasSource || hasTarget) {
                if (stopRequested.getAsBoolean()) {
                    break;
                }
                if (hasSource && hasTarget) {
                    sourceRows++;
                    targetRows++;
                    List<LogicalValue> sourceValues = sourceHandler.getValues(sourceResult, sourceMeta);
                    List<LogicalValue> targetValues = targetHandler.getValues(targetResult, targetMeta);
                    context.clear();
                    for (int i = 0; i < columnCount; i++) {
                        serializer.serialize(sourceValues.get(i), targetValues.get(i), i, context);
                        context.source().append(COLUMN_SEPARATOR);
                        context.target().append(COLUMN_SEPARATOR);
                    }
                    java.lang.String pk = primaryKey(sourceResult, sourcePkIndexes);
                    if (hash(context.source()) == hash(context.target())) {
                        matchedRows++;
                        consistent.add(pk);
                    } else {
                        mismatchedRows++;
                        different.add(pk);
                    }
                    maybeFlush(store, sourceTable, consistent, different, sourceOnly, targetOnly);
                    hasSource = sourceResult.next();
                    hasTarget = targetResult.next();
                } else if (hasSource) {
                    sourceRows++;
                    sourceOnlyRows++;
                    sourceOnly.add(primaryKey(sourceResult, sourcePkIndexes));
                    maybeFlush(store, sourceTable, consistent, different, sourceOnly, targetOnly);
                    hasSource = sourceResult.next();
                } else {
                    targetRows++;
                    targetOnlyRows++;
                    targetOnly.add(primaryKey(targetResult, targetPkIndexes));
                    maybeFlush(store, sourceTable, consistent, different, sourceOnly, targetOnly);
                    hasTarget = targetResult.next();
                }
            }
        }
        flush(store, sourceTable, consistent, different, sourceOnly, targetOnly);
        param.logListener().emit(MigrationLogLevel.INFO,
                "data check " + sourceTable + " -> " + targetTable
                        + ": source=" + sourceRows + ", target=" + targetRows
                        + ", matched=" + matchedRows + ", mismatched=" + mismatchedRows
                        + ", sourceOnly=" + sourceOnlyRows + ", targetOnly=" + targetOnlyRows);
    }

    private static void maybeFlush(
            InconsistentRowStore store,
            TableId table,
            List<java.lang.String> consistent,
            List<java.lang.String> different,
            List<java.lang.String> sourceOnly,
            List<java.lang.String> targetOnly) {
        if (consistent.size() >= FLUSH_ROWS
                || different.size() >= FLUSH_ROWS
                || sourceOnly.size() >= FLUSH_ROWS
                || targetOnly.size() >= FLUSH_ROWS) {
            flush(store, table, consistent, different, sourceOnly, targetOnly);
        }
    }

    private static void flush(
            InconsistentRowStore store,
            TableId table,
            List<java.lang.String> consistent,
            List<java.lang.String> different,
            List<java.lang.String> sourceOnly,
            List<java.lang.String> targetOnly) {
        if (!consistent.isEmpty()) {
            store.markConsistent(table, consistent);
            consistent.clear();
        }
        if (!different.isEmpty()) {
            store.markInconsistent(table, different, InconsistentRowStore.FLAG_DIFFERENT);
            different.clear();
        }
        if (!sourceOnly.isEmpty()) {
            store.markInconsistent(table, sourceOnly, InconsistentRowStore.FLAG_SOURCE_ONLY);
            sourceOnly.clear();
        }
        if (!targetOnly.isEmpty()) {
            store.markInconsistent(table, targetOnly, InconsistentRowStore.FLAG_TARGET_ONLY);
            targetOnly.clear();
        }
    }

    private static PreparedStatement streamingStatement(
            java.sql.Connection connection, java.lang.String sql, DatabaseType type)
            throws SQLException {
        PreparedStatement statement = connection.prepareStatement(
                sql, ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY);
        // MySQL streams row by row only with Integer.MIN_VALUE; PostgreSQL uses the fetch size as
        // its cursor batch size.
        statement.setFetchSize(type == DatabaseType.MYSQL ? Integer.MIN_VALUE : 1000);
        return statement;
    }

    private static java.lang.String selectSql(
            TableId tableId, TableDefinition definition, JdbcConnection connection)
            throws SQLException {
        java.lang.String quote = quoteString(connection);
        StringBuilder sql = new StringBuilder("SELECT ");
        List<ColumnDefinition> columns = definition.getColumnDefinitions();
        for (int i = 0; i < columns.size(); i++) {
            if (i > 0) {
                sql.append(", ");
            }
            sql.append(identifier(columns.get(i).getName(), quote));
        }
        sql.append(" FROM ").append(qualifiedName(tableId, quote));
        List<java.lang.String> primaryKey = definition.getPrimaryKeyColumNames();
        sql.append(" ORDER BY ");
        for (int i = 0; i < primaryKey.size(); i++) {
            if (i > 0) {
                sql.append(", ");
            }
            sql.append(identifier(primaryKey.get(i), quote));
        }
        return sql.toString();
    }

    private static int[] primaryKeyIndexes(TableDefinition definition) {
        List<ColumnDefinition> columns = definition.getColumnDefinitions();
        List<java.lang.String> primaryKey = definition.getPrimaryKeyColumNames();
        int[] indexes = new int[primaryKey.size()];
        for (int i = 0; i < primaryKey.size(); i++) {
            indexes[i] = -1;
            for (int j = 0; j < columns.size(); j++) {
                if (columns.get(j).getName().equalsIgnoreCase(primaryKey.get(i))) {
                    indexes[i] = j;
                    break;
                }
            }
            if (indexes[i] < 0) {
                throw new IllegalStateException(
                        "primary key column not found in metadata: " + primaryKey.get(i));
            }
        }
        return indexes;
    }

    private static java.lang.String primaryKey(ResultSet result, int[] indexes)
            throws SQLException {
        List<java.lang.String> values = new ArrayList<>(indexes.length);
        for (int index : indexes) {
            values.add(result.getString(index + 1));
        }
        return InconsistentRowStore.encode(values);
    }

    private static java.lang.String quoteString(JdbcConnection connection) throws SQLException {
        java.lang.String quote = connection.connection().getMetaData().getIdentifierQuoteString();
        return quote == null || quote.isBlank() ? "" : quote.trim();
    }

    private static java.lang.String qualifiedName(TableId tableId, java.lang.String quote) {
        if (tableId.getSchema() == null || tableId.getSchema().isBlank()) {
            return identifier(tableId.getTable(), quote);
        }
        return identifier(tableId.getSchema(), quote) + "." + identifier(tableId.getTable(), quote);
    }

    private static java.lang.String identifier(java.lang.String name, java.lang.String quote) {
        if (quote == null || quote.isEmpty()) {
            return name;
        }
        return quote + name.replace(quote, quote + quote) + quote;
    }

    /** 64-bit FNV-1a: cheap, allocation free, and collision-safe enough for a per-row comparison. */
    private static long hash(CharSequence text) {
        long hash = 0xcbf29ce484222325L;
        for (int i = 0; i < text.length(); i++) {
            hash ^= text.charAt(i);
            hash *= 0x100000001b3L;
        }
        return hash;
    }

    private static void warn(DataCheckParam param, TableId table, java.lang.String message) {
        param.logListener().emit(MigrationLogLevel.WARNING,
                "data check skipped for " + table + ": " + message);
    }
}
