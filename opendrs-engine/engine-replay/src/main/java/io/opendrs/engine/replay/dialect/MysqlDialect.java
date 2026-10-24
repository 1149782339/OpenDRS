package io.opendrs.engine.replay.dialect;

import io.opendrs.common.metadata.DatabaseType;
import io.opendrs.common.metadata.TableId;
import io.opendrs.engine.replay.ReplayConfig;
import io.opendrs.engine.replay.relation.ColumnDefinition;
import io.opendrs.engine.replay.sql.ExpressionBuilder;
import io.opendrs.engine.replay.sql.IdentifierRule;
import io.opendrs.engine.replay.sql.SQLState;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

/**
 * MySQL dialect, override common database dialect method {@link CommonDatabaseDialect}.
 *
 * <p>Needed for bidirectional sync (MySQL → MySQL replay) and any MySQL-as-target task.
 * MySQL has no schema below the database: the source schema name is used as the JDBC catalog,
 * identifiers quote with backticks, and upsert is {@code ON DUPLICATE KEY UPDATE}.
 *
 */
public class MysqlDialect extends CommonDatabaseDialect {

    public MysqlDialect(ReplayConfig config) {
        super(config);
    }

    @Override
    protected IdentifierRule getIdentifierRule() {
        return new IdentifierRule(".", "`");
    }

    /**
     * Get upsert sql statement.
     * MySQL upsert sql statement example:
     * insert into `bidir_test`.`sync_test`(`id`,`payload`) values(?,?)
     * on duplicate key update `payload`=values(`payload`);
     *
     * @param tableId                table identifier {@link TableId}
     * @param pkColumnDefinitions    primary key column definitions
     * @param nonPkColumnDefinitions non-primary key column definitions
     * @return upsert sql statement
     */
    @Override
    protected String getUpsertSql(TableId tableId, List<ColumnDefinition> pkColumnDefinitions, List<ColumnDefinition> nonPkColumnDefinitions) {
        final ExpressionBuilder expressionBuilder = createExpressionBuilder();
        final ExpressionBuilder.Transformer<ColumnDefinition> transformer =
            (builder, colDef) -> builder.append(colDef).append("=values(").append(colDef).append(")");
        expressionBuilder.append("insert into ")
            .append(tableId)
            .append("(")
            .listBuilder()
            .delimitedBy(",")
            .of(pkColumnDefinitions, nonPkColumnDefinitions);
        expressionBuilder.append(") ")
            .append("values(")
            .appendMulti(",", "?", pkColumnDefinitions.size() + nonPkColumnDefinitions.size())
            .append(") ")
            .append("on duplicate key update ");
        if (nonPkColumnDefinitions.isEmpty()) {
            // MySQL has no "do nothing" form; updating a PK to itself is the harmless equivalent.
            expressionBuilder.listBuilder()
                .delimitedBy(",")
                .transformedBy((builder, colDef) -> builder.append(colDef).append("=").append(colDef))
                .of(pkColumnDefinitions);
        } else {
            expressionBuilder.listBuilder()
                .delimitedBy(",")
                .transformedBy(transformer)
                .of(nonPkColumnDefinitions);
        }
        return expressionBuilder.toString();
    }

    @Override
    public void executeDDL(TableId tableId, String ddl, Connection connection) throws SQLException {
        // MySQL has no per-session schema switch; the connection URL already pins the database.
        try (Statement statement = connection.createStatement()) {
            statement.execute(ddl);
        }
    }

    /**
     * Normalize the value of SQLSTATE returned by MySQL to a unified type {@link SQLState}.
     *
     * @param sqlState value from {@link SQLException}
     * @return a unified enum type SQLSTATE {@link SQLState}
     */
    @Override
    public SQLState resolveSQLState(String sqlState) {
        if (sqlState == null) {
            return super.resolveSQLState(null);
        }
        switch (sqlState) {
            case "42000":
            case "1064":
                return SQLState.ERR_SYNTAX_ERR;
            case "23000":
                return SQLState.ERR_DUP_KEY;
            case "42S01":
                return SQLState.ERR_RELATION_EXISTS_ERROR;
            case "42S02":
                return SQLState.ERR_RELATION_NOT_EXISTS_ERROR;
            case "42S21":
                return SQLState.ERR_COLUMN_OF_RELATION_EXISTS_ERROR;
            case "42S22":
                return SQLState.ERR_COLUMN_OF_RELATION_NOT_EXISTS_ERROR;
            default:
                return super.resolveSQLState(sqlState);
        }
    }

    @Override
    public TableId resolveTableId(TableId tableId) {
        final String catalog = tableId.getCatalog();
        final String schema = tableId.getSchema();
        final String table = tableId.getTable();
        // MySQL: schema of the source maps onto the JDBC catalog (database).
        return new TableId(schema != null ? schema : catalog, null, table);
    }

    @Override
    public DatabaseType databaseType() {
        return DatabaseType.MYSQL;
    }

    public static class MysqlDialectProvider implements DatabaseDialectProvider {

        @Override
        public DatabaseDialect create(ReplayConfig config) {
            return new MysqlDialect(config);
        }

        @Override
        public String dialectName() {
            return "MysqlDialect";
        }
    }
}
