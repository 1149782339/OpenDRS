package io.opendrs.engine.datacheck;

import io.opendrs.engine.common.metadata.ColumnDefinition;
import io.opendrs.engine.common.metadata.DatabaseType;
import io.opendrs.engine.common.metadata.TableDefinition;
import io.opendrs.engine.common.metadata.TableId;
import io.opendrs.engine.datacheck.type.LogicalValue;
import io.opendrs.engine.datacheck.utils.DiffResult;
import io.opendrs.engine.datacheck.utils.HashUtil;
import io.opendrs.engine.jdbc.JdbcConnection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Shared row hashing, store flush, and SQL helpers for full and incremental checks. */
public final class DataCheckRows {

    /** Column boundary marker: without it ["a","bc"] and ["ab","c"] would hash the same. */
    public static final char COLUMN_SEPARATOR = '\u0000';

    /** Flush store writes every this many primary keys. */
    public static final int FLUSH_ROWS = 1_000;

    /** Hash bucket count; partitions the pk→rowHash map for the XOR fast path. */
    public static final int BUCKET_COUNT = 1_024;

    /** Max PK tuples per {@code IN (...)} query. */
    public static final int IN_CHUNK = 500;

    private DataCheckRows() {
    }

    /** 单侧行 → canonical 文本 → xxHash64。 */
    public static long rowHash(List<LogicalValue> values, StringBuilder buf) {
        buf.setLength(0);
        for (LogicalValue value : values) {
            value.serialize(buf);
            buf.append(COLUMN_SEPARATOR);
        }
        return HashUtil.getHash(buf);
    }

    /**
     * Diff → store：left/right/both 写入不一致；{@code checkedPks} 中其余视为一致并清掉历史标记
     * （含两边都没有的增量 PK）。
     */
    public static void applyDiff(
            InconsistentRowStore store,
            TableId table,
            DiffResult diff,
            List<String> checkedPks) {
        markInconsistentBatched(store, table, diff.both(), InconsistentRowStore.FLAG_DIFFERENT);
        markInconsistentBatched(store, table, diff.left(), InconsistentRowStore.FLAG_SOURCE_ONLY);
        markInconsistentBatched(store, table, diff.right(), InconsistentRowStore.FLAG_TARGET_ONLY);

        if (checkedPks == null || checkedPks.isEmpty()) {
            return;
        }
        Set<String> inconsistent = new HashSet<>(
                diff.left().size() + diff.right().size() + diff.both().size());
        inconsistent.addAll(diff.left());
        inconsistent.addAll(diff.right());
        inconsistent.addAll(diff.both());
        List<String> consistent = new ArrayList<>(Math.min(FLUSH_ROWS, checkedPks.size()));
        for (String pk : checkedPks) {
            if (!inconsistent.contains(pk)) {
                consistent.add(pk);
                if (consistent.size() >= FLUSH_ROWS) {
                    store.markConsistent(table, consistent);
                    consistent.clear();
                }
            }
        }
        if (!consistent.isEmpty()) {
            store.markConsistent(table, consistent);
        }
    }

    public static void markInconsistentBatched(
            InconsistentRowStore store, TableId table, List<String> pks, int flag) {
        if (pks == null || pks.isEmpty()) {
            return;
        }
        for (int start = 0; start < pks.size(); start += FLUSH_ROWS) {
            int end = Math.min(start + FLUSH_ROWS, pks.size());
            store.markInconsistent(table, pks.subList(start, end), flag);
        }
    }

    public static PreparedStatement streamingStatement(
            java.sql.Connection connection, String sql, DatabaseType type)
            throws SQLException {
        PreparedStatement statement = connection.prepareStatement(
                sql, ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY);
        statement.setFetchSize(type == DatabaseType.MYSQL ? Integer.MIN_VALUE : 1000);
        return statement;
    }

    public static String selectAllOrderByPkSql(
            TableId tableId, TableDefinition definition, JdbcConnection connection)
            throws SQLException {
        String quote = quoteString(connection);
        StringBuilder sql = new StringBuilder("SELECT ");
        appendColumnList(sql, definition.getColumnDefinitions(), quote);
        sql.append(" FROM ").append(qualifiedName(tableId, quote));
        List<String> primaryKey = definition.getPrimaryKeyColumNames();
        sql.append(" ORDER BY ");
        for (int i = 0; i < primaryKey.size(); i++) {
            if (i > 0) {
                sql.append(", ");
            }
            sql.append(identifier(primaryKey.get(i), quote));
        }
        return sql.toString();
    }

    public static void appendColumnList(
            StringBuilder sql, List<ColumnDefinition> columns, String quote) {
        for (int i = 0; i < columns.size(); i++) {
            if (i > 0) {
                sql.append(", ");
            }
            sql.append(identifier(columns.get(i).getName(), quote));
        }
    }

    public static int[] primaryKeyIndexes(TableDefinition definition) {
        List<ColumnDefinition> columns = definition.getColumnDefinitions();
        List<String> primaryKey = definition.getPrimaryKeyColumNames();
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

    public static String primaryKey(ResultSet result, int[] indexes) throws SQLException {
        return primaryKey(result, indexes, null, null);
    }

    /**
     * Encodes the current row's primary key using {@link PrimaryKeySupport} when column
     * metadata is supplied, so full-check bucket keys match incremental event encoding.
     */
    public static String primaryKey(
            ResultSet result, int[] indexes, TableId table, List<ColumnDefinition> pkColumns)
            throws SQLException {
        if (pkColumns != null && pkColumns.size() != indexes.length) {
            throw new IllegalArgumentException("pk column metadata does not match indexes");
        }
        List<String> values = new ArrayList<>(indexes.length);
        for (int i = 0; i < indexes.length; i++) {
            Object raw = result.getObject(indexes[i] + 1);
            if (result.wasNull() || raw == null) {
                throw new IllegalArgumentException(
                        "null primary key value is not allowed"
                                + (table == null ? "" : " on table " + table)
                                + (pkColumns == null ? "" : " (column " + pkColumns.get(i).getName() + ")"));
            }
            if (pkColumns == null) {
                String text = result.getString(indexes[i] + 1);
                if (text == null) {
                    throw new IllegalArgumentException("null primary key value is not allowed");
                }
                values.add(text);
            } else {
                values.add(PrimaryKeySupport.encodeColumn(table, pkColumns.get(i), raw));
            }
        }
        return InconsistentRowStore.encode(values);
    }

    public static String quoteString(JdbcConnection connection) throws SQLException {
        String quote = connection.connection().getMetaData().getIdentifierQuoteString();
        return quote == null || quote.isBlank() ? "" : quote.trim();
    }

    public static String qualifiedName(TableId tableId, String quote) {
        if (tableId.getSchema() == null || tableId.getSchema().isBlank()) {
            return identifier(tableId.getTable(), quote);
        }
        return identifier(tableId.getSchema(), quote) + "." + identifier(tableId.getTable(), quote);
    }

    public static String identifier(String name, String quote) {
        if (quote == null || quote.isEmpty()) {
            return name;
        }
        return quote + name.replace(quote, quote + quote) + quote;
    }
}
