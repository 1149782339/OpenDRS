package io.opendrs.engine.replay.applier;

import static io.opendrs.engine.replay.sql.SQLState.ERR_COLUMN_OF_RELATION_EXISTS_ERROR;
import static io.opendrs.engine.replay.sql.SQLState.ERR_COLUMN_OF_RELATION_NOT_EXISTS_ERROR;
import static io.opendrs.engine.replay.sql.SQLState.ERR_RELATION_EXISTS_ERROR;
import static io.opendrs.engine.replay.sql.SQLState.ERR_RELATION_NOT_EXISTS_ERROR;
import static io.opendrs.engine.replay.sql.SQLState.ERR_SCHEMA_EXISTS_ERROR;
import static io.opendrs.engine.replay.sql.SQLState.ERR_SCHEMA_NOT_EXISTS_ERROR;
import static io.opendrs.engine.replay.sql.SQLState.ERR_SYNTAX_ERR;

import io.opendrs.engine.replay.ReplayConfig;
import io.opendrs.engine.replay.GtidSequence;
import io.opendrs.engine.replay.annotation.ThreadSafe;
import io.opendrs.engine.replay.connection.JdbcConnection;
import io.opendrs.engine.replay.context.ConnectorContext;
import io.opendrs.engine.replay.context.TaskContext;
import io.opendrs.engine.ddl.DdlStatements;
import io.opendrs.engine.ddl.converters.ConversionConfiguration;
import io.opendrs.engine.ddl.converters.ConversionResult;
import io.opendrs.engine.ddl.converters.ConversionStatus;
import io.opendrs.engine.ddl.converters.SQLConverter;
import io.opendrs.engine.ddl.converters.SQLConverters;
import io.opendrs.engine.replay.dialect.DatabaseDialect;
import io.opendrs.engine.replay.dialect.DatabaseDialects;
import io.opendrs.common.metadata.DatabaseType;
import io.opendrs.engine.replay.event.ChangeEvent;
import io.opendrs.engine.replay.event.DataChangeEvent;
import io.opendrs.engine.replay.event.Operation;
import io.opendrs.engine.replay.event.SchemaChangeEvent;
import io.opendrs.engine.replay.event.TransactionEvent;
import io.opendrs.engine.replay.exception.ApplierException;
import io.opendrs.engine.replay.jdbc.StatementBinder;
import io.opendrs.engine.replay.metrics.ReplayMetrics;
import io.opendrs.engine.replay.relation.FieldsMetaData;
import io.opendrs.engine.replay.relation.TableDefinition;
import io.opendrs.engine.replay.relation.TableDefinitions;
import io.opendrs.common.metadata.TableId;
import io.opendrs.engine.replay.sql.SQLState;
import io.opendrs.engine.replay.util.TimeTracker;
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

    private final ReplayConfig config;

    private final ConnectorContext context;

    private StatementBinder statementBinder = null;

    private PreparedStatement preparedStatement = null;

    private Operation operation = null;

    private TableId tableId = null;

    private boolean enableUpsertMode = false;

    private final TimeTracker timeTracker;

    private final Map<DatabaseType, SQLConverter> sqlConverters;

    private final ConversionConfiguration conversionConfiguration;

    /** Bidirectional loop prevention: tag every applied transaction with a GTID. */
    private final boolean gtidTagging;
    private final GtidSequence fullLoadSequence;

    public JdbcApplier(ConnectorContext context, ReplayConfig config) {
        this.context = context;
        this.config = config;
        this.databaseDialect = DatabaseDialects.create(config);
        this.events = new ArrayList<>();
        this.timeTracker = new TimeTracker();
        this.gtidTagging = config.getGtidLoop() != null && config.getGtidLoop().taggingEnabled();
        this.fullLoadSequence = gtidTagging ? new GtidSequence(config.getGtidLoop().fullLoadNamespace()) : null;
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

    private void handleDataChangeEvent(Connection connection, DataChangeEvent event) throws SQLException {        final TableId resolvedTableId = databaseDialect.resolveTableId(event.getTableId());
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

    /**
     * @return true when this call committed the connection (so a caller tagging transactions with
     *     {@code gtid_next} knows the tag was consumed and the remainder needs a fresh one)
     */
    private boolean handleSchemaChangeEvent(Connection connection, SchemaChangeEvent event)
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
            // Session statements and MySQL-only table options: nothing to replay.
            LOGGER.info("skip ddl with no postgres equivalent: {}", event.getDDL());
            return false;
        }
        try {
            for (String statement : statements) {
                databaseDialect.executeDDL(resolvedTableId, statement, connection);
            }
            connection.commit();
            tableDefinitions.refresh(connection, resolvedTableId);
            LOGGER.info("Succeed to execute ddl '{}'", result);
            return true;
        } catch (SQLException e) {
            SQLState state = databaseDialect.resolveSQLState(e.getSQLState());
            if (state == ERR_RELATION_EXISTS_ERROR || state == ERR_RELATION_NOT_EXISTS_ERROR
                    || state == ERR_COLUMN_OF_RELATION_EXISTS_ERROR || state == ERR_COLUMN_OF_RELATION_NOT_EXISTS_ERROR
                    || state == ERR_SCHEMA_EXISTS_ERROR || state == ERR_SCHEMA_NOT_EXISTS_ERROR) {
                LOGGER.info("Failed to execute ddl ({}), SQLState: {}. ignore it", result, state);
                connection.rollback();
                return false;
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
                metrics().applyFailed(config.getDirection(), taskId());
                throw new ApplierException("failed to apply change events", e);
            } catch (Throwable e) {
                metrics().applyFailed(config.getDirection(), taskId());
                throw new ApplierException("failed to apply change events", e);
            }
        }
    }

    private ReplayMetrics metrics() {
        return config.getMetrics();
    }

    private long taskId() {
        return config.getTaskId() == null ? -1L : config.getTaskId();
    }

    private void doApply(Collection<ChangeEvent> events) throws SQLException, ApplierException {
        if (events.isEmpty()) {
            return;
        }
        Connection connection = jdbcConnection.connection();
        try {
            connection.setAutoCommit(false);
            // gtid_next must be assigned before the batch's first statement (MySQL refuses to
            // change it inside a transaction) and re-assigned after any mid-batch commit, because
            // a commit consumes the tag. The tag the peer capture drops: the source record's own
            // GTID when the whole batch shares one, otherwise a fresh synthetic full-load GTID.
            if (gtidTagging) {
                assignGtid(connection, initialBatchGtid(connection, events));
            }
            for (ChangeEvent event : events) {
                if (event instanceof DataChangeEvent dataChangeEvent) {
                    handleDataChangeEvent(connection, dataChangeEvent);
                } else if (event instanceof SchemaChangeEvent schemaChangeEvent) {
                    if (handleSchemaChangeEvent(connection, schemaChangeEvent) && gtidTagging) {
                        assignGtid(connection, fullLoadSequence.next(connection));
                    }
                } else {
                    LOGGER.debug("ignore the event");
                }
            }
            long execStart = System.nanoTime();
            flush(true);
            long execNanos = System.nanoTime() - execStart;
            long commitStart = System.nanoTime();
            connection.commit();
            long commitNanos = System.nanoTime() - commitStart;
            // 一个 apply() 调用 = 一个调度单元：经由 TransactionApplier 时恰好是一个源端
            // 事务（以事务元数据的 END 标记计数，每事务恰好一个）；经由 TableApplier 的
            // 全量分片没有事务标记，不计入 TPS。DDL 单独派发的事务同样无 END 标记——
            // 它是对目标库的一次真实提交，但不代表一个源端事务。
            int txCount = 0;
            int rowCount = 0;
            for (ChangeEvent event : events) {
                if (event instanceof DataChangeEvent) {
                    rowCount++;
                } else if (event instanceof TransactionEvent txEvent
                        && txEvent.getStatus() == TransactionEvent.Status.END) {
                    txCount++;
                }
            }
            metrics().appliedRows(config.getDirection(), taskId(), rowCount);
            if (txCount > 0) {
                // 批次内每个源端事务都继承本次批次的执行/提交耗时（不做行级摊销），
                // Timer 平均下来才是“N 个事务时延的算术平均”。
                metrics().txCommitted(config.getDirection(), taskId(), txCount,
                        execNanos / 1e6, commitNanos / 1e6);
            }
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

    /**
     * Tag the current transaction with an explicit GTID. Idempotence comes free: MySQL silently
     * skips a transaction whose GTID is already in {@code gtid_executed}, so a crash-replay of a
     * committed batch is a no-op.
     */
    private static void assignGtid(Connection connection, String gtid) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("SET SESSION gtid_next='" + gtid + "'");
        }
    }

    /**
     * GTID for the batch transaction: the source GTID shared by every event (incremental replay),
     * or a fresh synthetic full-load GTID when the batch mixes GTIDs or has none (snapshot rows
     * carry no GTID).
     */
    private String initialBatchGtid(Connection connection, Collection<ChangeEvent> events)
            throws SQLException {
        String shared = null;
        boolean mixed = false;
        for (ChangeEvent event : events) {
            String gtid = event.getGtid();
            if (gtid == null) {
                continue;
            }
            if (shared == null) {
                shared = gtid;
            } else if (!shared.equals(gtid)) {
                mixed = true;
                break;
            }
        }
        return mixed || shared == null ? fullLoadSequence.next(connection) : shared;
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
