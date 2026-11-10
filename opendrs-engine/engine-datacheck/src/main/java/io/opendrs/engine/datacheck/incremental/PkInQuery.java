package io.opendrs.engine.datacheck.incremental;

import io.opendrs.engine.common.metadata.TableDefinition;
import io.opendrs.engine.common.metadata.TableId;
import io.opendrs.engine.datacheck.DataCheckRows;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;

/** Builds a bound {@code SELECT * FROM t WHERE (pk...) IN ((?,...),...)} query. */
public final class PkInQuery {

    private PkInQuery() {
    }

    public static String selectSql(
            TableId tableId, TableDefinition definition, String quote, int rowCount) {
        if (rowCount <= 0) {
            throw new IllegalArgumentException("rowCount must be > 0");
        }
        List<String> pk = definition.getPrimaryKeyColumNames();
        if (pk.isEmpty()) {
            throw new IllegalArgumentException("table has no primary key: " + tableId);
        }
        StringBuilder sql = new StringBuilder("SELECT ");
        DataCheckRows.appendColumnList(sql, definition.getColumnDefinitions(), quote);
        sql.append(" FROM ").append(DataCheckRows.qualifiedName(tableId, quote));
        sql.append(" WHERE ");
        appendInPredicate(sql, pk, quote, rowCount);
        return sql.toString();
    }

    public static void appendInPredicate(
            StringBuilder sql, List<String> pkColumns, String quote, int rowCount) {
        boolean composite = pkColumns.size() > 1;
        if (composite) {
            sql.append('(');
            for (int i = 0; i < pkColumns.size(); i++) {
                if (i > 0) {
                    sql.append(", ");
                }
                sql.append(DataCheckRows.identifier(pkColumns.get(i), quote));
            }
            sql.append(") IN (");
            for (int r = 0; r < rowCount; r++) {
                if (r > 0) {
                    sql.append(", ");
                }
                sql.append('(');
                for (int c = 0; c < pkColumns.size(); c++) {
                    if (c > 0) {
                        sql.append(',');
                    }
                    sql.append('?');
                }
                sql.append(')');
            }
            sql.append(')');
        } else {
            sql.append(DataCheckRows.identifier(pkColumns.get(0), quote));
            sql.append(" IN (");
            for (int r = 0; r < rowCount; r++) {
                if (r > 0) {
                    sql.append(',');
                }
                sql.append('?');
            }
            sql.append(')');
        }
    }

    public static void bind(PreparedStatement statement, List<List<Object>> rows)
            throws SQLException {
        bind(statement, rows, null);
    }

    /**
     * Binds already-normalized values. When {@code sqlTypes} is provided (one JDBC type per
     * primary-key column), each placeholder uses {@code setObject(index, value, sqlType)}.
     */
    public static void bind(PreparedStatement statement, List<List<Object>> rows, int[] sqlTypes)
            throws SQLException {
        int index = 1;
        for (List<Object> row : rows) {
            for (int c = 0; c < row.size(); c++) {
                Object value = row.get(c);
                if (sqlTypes != null && c < sqlTypes.length) {
                    statement.setObject(index++, value, sqlTypes[c]);
                } else {
                    statement.setObject(index++, value);
                }
            }
        }
    }
}
