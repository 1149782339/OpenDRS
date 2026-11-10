package io.opendrs.engine.datacheck.incremental;

import io.opendrs.engine.common.metadata.ColumnDefinition;
import io.opendrs.engine.common.metadata.TableDefinition;
import io.opendrs.engine.common.metadata.TableId;
import io.opendrs.engine.datacheck.DataCheckRows;
import io.opendrs.engine.datacheck.PrimaryKeySupport;
import io.opendrs.engine.datacheck.InconsistentRowStore;
import io.opendrs.engine.datacheck.type.LogicalValue;
import io.opendrs.engine.datacheck.type.handler.TypeHandler;
import io.opendrs.engine.datacheck.type.handler.TypeHandlerFactory;
import io.opendrs.engine.datacheck.utils.HashBuckets;
import io.opendrs.engine.datacheck.utils.HashUtil;
import io.opendrs.engine.jdbc.JdbcConnection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/** Queries both sides with a bound PK IN list and diffs through HashBuckets. */
public final class JdbcPkBatchChecker implements PkBatchChecker {

    private final JdbcConnection source;
    private final JdbcConnection target;
    private final TypeHandler sourceHandler;
    private final TypeHandler targetHandler;
    private final java.util.function.Function<TableId, TableDefinition> sourceDefinitions;
    private final java.util.function.Function<TableId, TableDefinition> targetDefinitions;

    public JdbcPkBatchChecker(
            JdbcConnection source,
            JdbcConnection target,
            java.util.function.Function<TableId, TableDefinition> sourceDefinitions,
            java.util.function.Function<TableId, TableDefinition> targetDefinitions) {
        this.source = source;
        this.target = target;
        this.sourceHandler = TypeHandlerFactory.of(source.type());
        this.targetHandler = TypeHandlerFactory.of(target.type());
        this.sourceDefinitions = sourceDefinitions;
        this.targetDefinitions = targetDefinitions;
    }

    @Override
    public void check(CheckedTablePair tables, List<PkRow> keys, InconsistentRowStore store) {
        if (keys == null || keys.isEmpty()) {
            return;
        }
        TableDefinition sourceDef = sourceDefinitions.apply(tables.source());
        TableDefinition targetDef = targetDefinitions.apply(tables.target());
        if (sourceDef == null || targetDef == null) {
            throw new IllegalStateException("missing table definition for " + tables.source());
        }
        try {
            for (int start = 0; start < keys.size(); start += DataCheckRows.IN_CHUNK) {
                int end = Math.min(start + DataCheckRows.IN_CHUNK, keys.size());
                checkChunk(tables, sourceDef, targetDef, keys.subList(start, end), store);
            }
        } catch (SQLException ex) {
            throw new IllegalStateException(
                    "incremental check query failed for " + tables.source() + ": " + ex.getMessage(), ex);
        }
    }

    private void checkChunk(
            CheckedTablePair tables,
            TableDefinition sourceDef,
            TableDefinition targetDef,
            List<PkRow> chunk,
            InconsistentRowStore store)
            throws SQLException {
        HashBuckets sourceBuckets = new HashBuckets(DataCheckRows.BUCKET_COUNT);
        HashBuckets targetBuckets = new HashBuckets(DataCheckRows.BUCKET_COUNT);
        StringBuilder buf = new StringBuilder();
        fill(source, tables.source(), sourceDef, sourceHandler, chunk, sourceBuckets, buf);
        fill(target, tables.target(), targetDef, targetHandler, chunk, targetBuckets, buf);
        List<String> checked = new ArrayList<>(chunk.size());
        for (PkRow row : chunk) {
            checked.add(row.encoded());
        }
        DataCheckRows.applyDiff(store, tables.source(), sourceBuckets.diff(targetBuckets), checked);
        sourceBuckets.clear();
        targetBuckets.clear();
    }

    private static void fill(
            JdbcConnection connection,
            TableId table,
            TableDefinition definition,
            TypeHandler handler,
            List<PkRow> chunk,
            HashBuckets buckets,
            StringBuilder buf)
            throws SQLException {
        String quote = DataCheckRows.quoteString(connection);
        String sql = PkInQuery.selectSql(table, definition, quote, chunk.size());
        int[] pkIndexes = DataCheckRows.primaryKeyIndexes(definition);
        List<ColumnDefinition> pkColumns = PrimaryKeySupport.primaryKeyColumns(definition);
        int[] sqlTypes = new int[pkColumns.size()];
        for (int i = 0; i < pkColumns.size(); i++) {
            sqlTypes[i] = pkColumns.get(i).getJdbcType();
        }
        List<List<Object>> bind = new ArrayList<>(chunk.size());
        for (PkRow row : chunk) {
            bind.add(row.bindValues());
        }
        try (PreparedStatement statement = connection.connection().prepareStatement(sql)) {
            PkInQuery.bind(statement, bind, sqlTypes);
            try (ResultSet result = statement.executeQuery()) {
                ResultSetMetaData meta = result.getMetaData();
                while (result.next()) {
                    List<LogicalValue> values = handler.getValues(result, meta);
                    String pk = DataCheckRows.primaryKey(result, pkIndexes, table, pkColumns);
                    buckets.add(pk, HashUtil.getHash(pk), DataCheckRows.rowHash(values, buf));
                }
            }
        }
    }
}
