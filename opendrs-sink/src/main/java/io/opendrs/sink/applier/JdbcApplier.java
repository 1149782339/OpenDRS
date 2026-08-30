/*
 *  Copyright DbSink Authors.
 *  This source code is licensed under the Apache License Version 2.0, available
 *  at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.opendrs.sink.applier;

import static io.opendrs.sink.sql.SQLState.ERR_COLUMN_OF_RELATION_EXISTS_ERROR;
import static io.opendrs.sink.sql.SQLState.ERR_COLUMN_OF_RELATION_NOT_EXISTS_ERROR;
import static io.opendrs.sink.sql.SQLState.ERR_RELATION_EXISTS_ERROR;
import static io.opendrs.sink.sql.SQLState.ERR_RELATION_NOT_EXISTS_ERROR;
import static io.opendrs.sink.sql.SQLState.ERR_SCHEMA_EXISTS_ERROR;
import static io.opendrs.sink.sql.SQLState.ERR_SCHEMA_NOT_EXISTS_ERROR;
import static io.opendrs.sink.sql.SQLState.ERR_SYNTAX_ERR;

import io.opendrs.sink.SinkConfig;
import io.opendrs.sink.annotation.ThreadSafe;
import io.opendrs.sink.connection.JdbcConnection;
import io.opendrs.sink.context.ConnectorContext;
import io.opendrs.sink.context.TaskContext;
import io.opendrs.sink.ddl.DdlStatements;
import io.opendrs.sink.ddl.converters.ConversionConfiguration;
import io.opendrs.sink.ddl.converters.ConversionResult;
import io.opendrs.sink.ddl.converters.ConversionStatus;
import io.opendrs.sink.ddl.converters.SQLConverter;
import io.opendrs.sink.ddl.converters.SQLConverters;
import io.opendrs.sink.dialect.DatabaseDialect;
import io.opendrs.sink.dialect.DatabaseDialects;
import io.opendrs.sink.dialect.DatabaseType;
import io.opendrs.sink.event.ChangeEvent;
import io.opendrs.sink.event.DataChangeEvent;
import io.opendrs.sink.event.Operation;
import io.opendrs.sink.event.SchemaChangeEvent;
import io.opendrs.sink.exception.ApplierException;
import io.opendrs.sink.jdbc.StatementBinder;
import io.opendrs.sink.relation.FieldsMetaData;
import io.opendrs.sink.relation.TableDefinition;
import io.opendrs.sink.relation.TableDefinitions;
import io.opendrs.sink.relation.TableId;
import io.opendrs.sink.sql.SQLState;
import io.opendrs.sink.util.TimeTracker;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.kafka.connect.errors.ConnectException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Jdbc applier, execute dml(insert,update,delete,upsert) or ddl
 */
@ThreadSafe
public class JdbcApplier implements Applier<Collection<ChangeEvent>> {
    private static final Logger LOGGER = LoggerFactory.getLogger(JdbcApplier.class);

    private static final int MAX_UPSERT_ELAPSED_TIME = 120000;

    private final DatabaseDialect databaseDialect;

    private final JdbcConnection jdbcConnection;

    private final TableDefinitions tableDefinitions;

    private final List<DataChangeEvent> events;

    private final SinkConfig config;

    private final ConnectorContext context;

    private StatementBinder statementBinder = null;

    private PreparedStatement preparedStatement = null;

    private Operation operation = null;

    private TableId tableId = null;

    private boolean enableUpsertMode = false;

    private final TimeTracker timeTracker;

    private final Map<DatabaseType, SQLConverter> sqlConverters;

    private final ConversionConfiguration conversionConfiguration;

    public JdbcApplier(ConnectorContext context, SinkConfig config) {
        this.context = context;
        this.config = config;
        this.databaseDialect = DatabaseDialects.create(config);
        this.events = new ArrayList<>();
        this.timeTracker = new TimeTracker();
        this.tableDefinitions = new TableDefinitions(databaseDialect);
        this.jdbcConnection = new JdbcConnection(databaseDialect, config);
        this.sqlConverters = new HashMap<>();
        this.conversionConfiguration = new ConversionConfiguration(
                config.getTableNamingStrategy(), config.getColumnNamingStrategy());
    }

    @Override
    public void prepare(TaskContext taskContext) {
        // no Kafka offsets in the embedded path
    }

    private void handleDataChangeEvent(Connection connection, DataChangeEvent event) throws SQLException {
        final TableId resolvedTableId = databaseDialect.resolveTableId(event.getTableId());
        final TableDefinition tableDefinition = tableDefinitions.get(connection, resolvedTableId);
        if (tableDefinition == null) {
            throw new ConnectException("table \"" + resolvedTableId + "\" doesn't exist!");
        }
        Operation nextOperation;
        if (enableUpsertMode && (event.getOperation() == Operation.CREATE || event.getOperation() == Operation.READ)) {
            nextOperation = Operation.UPSERT;
        } else {
            nextOperation = event.getOperation();
        }
        if (this.operation == null) {
            this.operation = event.getOperation();
        }
        if (this.tableId == null) {
            this.tableId = resolvedTableId;
        }
        if (!nextOperation.equals(this.operation) || !resolvedTableId.equals(this.tableId)) {
            flush(true);
            this.operation = nextOperation;
            this.tableId = resolvedTableId;
        }
        if (preparedStatement == null) {
            FieldsMetaData fieldsMetaData = event.getFieldsMetaData();
            String statement = buildStatement(fieldsMetaData, tableDefinition);
            preparedStatement = databaseDialect.createPreparedStatement(statement, connection);
            statementBinder = databaseDialect.createStatementBinder(fieldsMetaData, tableDefinition);
        }
        this.events.add(event);
    }

    private void handleSchemaChangeEvent(Connection connection, SchemaChangeEvent event)
            throws SQLException, ApplierException {
        flush(true);
        TableId resolvedTableId = databaseDialect.resolveTableId(event.getTableId());
        ensureSchema(connection, resolvedTableId);
        SQLConverter sqlConverter = getSQLConverter(event);
        ConversionResult result = sqlConverter.convert(event.getDDL());
        if (result.getStatus() == ConversionStatus.FAILED) {
            throw new ApplierException("Failed to convert ddl, detail: " + result.getErrors() + "\"");
        }
        List<String> statements = DdlStatements.executableForPostgres(result.getStatements());
        if (statements.isEmpty()) {
            LOGGER.info("skip mysql session/non-ddl schema event: {}", event.getDDL());
            return;
        }
        try {
            for (String statement : statements) {
                databaseDialect.executeDDL(resolvedTableId, statement, connection);
            }
            connection.commit();
            tableDefinitions.refresh(connection, resolvedTableId);
            LOGGER.info("Succeed to execute ddl '{}'", result);
        } catch (SQLException e) {
            SQLState state = databaseDialect.resolveSQLState(e.getSQLState());
            if (state == ERR_RELATION_EXISTS_ERROR || state == ERR_RELATION_NOT_EXISTS_ERROR
                    || state == ERR_COLUMN_OF_RELATION_EXISTS_ERROR || state == ERR_COLUMN_OF_RELATION_NOT_EXISTS_ERROR
                    || state == ERR_SCHEMA_EXISTS_ERROR || state == ERR_SCHEMA_NOT_EXISTS_ERROR) {
                LOGGER.info("Failed to execute ddl ({}), SQLState: {}. ignore it", result, state);
                connection.rollback();
                return;
            }
            if (state == ERR_SYNTAX_ERR) {
                LOGGER.error("Failed to execute ddl due to syntax error, please check conversion result({})", result);
            }
            throw e;
        }
    }

    private void ensureSchema(Connection connection, TableId tableId) throws SQLException {
        if (tableId == null || tableId.getSchema() == null || tableId.getSchema().isBlank()) {
            return;
        }
        String schema = tableId.getSchema().replace("\"", "\"\"");
        try (Statement statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA IF NOT EXISTS \"" + schema + "\"");
        }
    }

    private SQLConverter getSQLConverter(SchemaChangeEvent event) {
        DatabaseType sourceType = event.getDatabaseType();
        DatabaseType targetType = databaseDialect.databaseType();
        SQLConverter sqlConverter = sqlConverters.get(event.getDatabaseType());
        if (sqlConverter == null) {
            sqlConverter = SQLConverters.create(sourceType, targetType, conversionConfiguration);
            sqlConverters.put(sourceType, sqlConverter);
        }
        return sqlConverter;
    }

    @Override
    public synchronized void apply(Collection<ChangeEvent> events) throws ApplierException {
        for (int retry = 0; retry < config.getConnectionRetriesMax(); retry++) {
            try {
                doApply(events);
                return;
            } catch (SQLException e) {
                if (retry < config.getConnectionRetriesMax() - 1) {
                    LOGGER.warn("failed to apply change events, retry to apply", e);
                    try {
                        Thread.sleep(config.getConnectionBackoff());
                    } catch (InterruptedException ex) {
                        Thread.currentThread().interrupt();
                        if (context != null && !context.isRunning()) {
                            throw new ApplierException("connector is closed!");
                        }
                    }
                    continue;
                }
                throw new ApplierException("failed to apply change events", e);
            } catch (Throwable e) {
                throw new ApplierException("failed to apply change events", e);
            }
        }
    }

    private void doApply(Collection<ChangeEvent> events) throws SQLException, ApplierException {
        if (events.isEmpty()) {
            return;
        }
        Connection connection = jdbcConnection.connection();
        try {
            connection.setAutoCommit(false);
            for (ChangeEvent event : events) {
                if (event instanceof DataChangeEvent dataChangeEvent) {
                    handleDataChangeEvent(connection, dataChangeEvent);
                } else if (event instanceof SchemaChangeEvent schemaChangeEvent) {
                    handleSchemaChangeEvent(connection, schemaChangeEvent);
                } else {
                    LOGGER.debug("ignore the event");
                }
            }
            flush(true);
            connection.commit();
            if (enableUpsertMode && timeTracker.isTimeElapsed(MAX_UPSERT_ELAPSED_TIME)) {
                enableUpsertMode = false;
            }
        } catch (SQLException e) {
            rollback(connection);
            if (!ignorable(e)) {
                throw e;
            }
        } finally {
            flush(false);
        }
    }

    private void rollback(Connection connection) {
        try {
            connection.rollback();
        } catch (SQLException sqlException) {
            LOGGER.debug("failed to rollback, ignore it", sqlException);
        }
    }

    private boolean ignorable(SQLException e) {
        SQLState sqlState = databaseDialect.resolveSQLState(e.getSQLState());
        if (sqlState == SQLState.ERR_DUP_KEY) {
            enableUpsertMode = true;
            return false;
        }
        if (sqlState == ERR_RELATION_EXISTS_ERROR || sqlState == ERR_RELATION_NOT_EXISTS_ERROR) {
            LOGGER.warn("relation already exists or not exists, ignore it");
            return true;
        }
        return false;
    }

    @Override
    public void release() {
        jdbcConnection.close();
    }

    private void flush(boolean needExecute) throws SQLException {
        if (needExecute && !events.isEmpty()) {
            for (DataChangeEvent event : events) {
                switch (operation) {
                    case DELETE:
                        statementBinder.bindDeleteStatement(preparedStatement, event);
                        break;
                    case UPDATE:
                        statementBinder.bindUpdateStatement(preparedStatement, event);
                        break;
                    case CREATE:
                    case READ:
                        statementBinder.bindInsertStatement(preparedStatement, event);
                        break;
                    case UPSERT:
                        statementBinder.bindUpsertStatement(preparedStatement, event);
                        break;
                    default:
                        throw new IllegalArgumentException("not implement!");
                }
                preparedStatement.addBatch();
            }
            preparedStatement.executeBatch();
        }
        if (preparedStatement != null) {
            preparedStatement.close();
        }
        preparedStatement = null;
        events.clear();
    }

    private String buildStatement(FieldsMetaData fieldsMetaData, TableDefinition tableDefinition) {
        switch (operation) {
            case UPDATE:
                return databaseDialect.buildUpdateStatement(fieldsMetaData, tableDefinition);
            case UPSERT:
                return databaseDialect.buildUpsertStatement(fieldsMetaData, tableDefinition);
            case CREATE:
            case READ:
                return databaseDialect.buildInsertStatement(fieldsMetaData, tableDefinition);
            case DELETE:
                return databaseDialect.buildDeleteStatement(fieldsMetaData, tableDefinition);
            default:
                throw new IllegalArgumentException("not implement!");
        }
    }

    /**
     * Apply already-converted PostgreSQL statements (used when snapshot schema events are missing
     * a CREATE TABLE and the service falls back to source {@code SHOW CREATE TABLE}).
     */
    public synchronized void applyConvertedDdl(TableId tableId, List<String> statements) throws ApplierException {
        try {
            Connection connection = jdbcConnection.connection();
            connection.setAutoCommit(false);
            TableId resolved = databaseDialect.resolveTableId(tableId);
            ensureSchema(connection, resolved);
            for (String statement : DdlStatements.executableForPostgres(statements)) {
                databaseDialect.executeDDL(resolved, statement, connection);
            }
            connection.commit();
            tableDefinitions.refresh(connection, resolved);
        } catch (SQLException e) {
            throw new ApplierException("failed to apply converted ddl", e);
        }
    }

    public DatabaseDialect dialect() {
        return databaseDialect;
    }
}
