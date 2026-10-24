package io.opendrs.engine.core;

import io.opendrs.common.log.MigrationLogLevel;
import io.opendrs.common.log.MigrationLogListener;
import io.opendrs.common.metadata.DatabaseType;
import io.opendrs.common.metadata.TableId;
import io.opendrs.common.naming.ColumnNamingStrategy;
import io.opendrs.common.naming.TableNamingStrategy;
import io.opendrs.engine.ddl.DdlStatements;
import io.opendrs.engine.ddl.converters.ConversionConfiguration;
import io.opendrs.engine.ddl.converters.ConversionResult;
import io.opendrs.engine.ddl.converters.ConversionStatus;
import io.opendrs.engine.ddl.converters.SQLConverters;
import io.opendrs.jdbc.JdbcConnection;
import io.opendrs.jdbc.JdbcConnectionConfig;
import io.opendrs.jdbc.JdbcConnectionFactory;
import io.opendrs.jdbc.metadata.Table;
import io.opendrs.jdbc.metadata.TableRef;
import io.opendrs.engine.replay.ReplayConfig;
import io.opendrs.engine.replay.dialect.DatabaseDialect;
import io.opendrs.engine.replay.dialect.DatabaseDialects;
import io.opendrs.engine.replay.exception.ApplierException;
import io.opendrs.engine.replay.mapping.MappingValidator;
import io.opendrs.engine.replay.mapping.TableSelection;
import io.opendrs.engine.replay.naming.DefaultColumnNamingStrategy;
import io.opendrs.engine.replay.naming.MappedTableNamingStrategy;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Creates target tables from the source's {@code SHOW CREATE TABLE} plus the MySQL→PostgreSQL
 * converter. Extracted from the capture path so structure migration is a standalone operation that
 * no longer runs implicitly before a snapshot.
 *
 * <p>Each table is committed on its own: a failure half way through leaves the already-created
 * tables in place, and a re-run skips them through {@code tableExists}. That makes the operation
 * idempotent without needing a whole-batch rollback.
 */
public final class SchemaMigrator {

    private static final Logger log = LoggerFactory.getLogger(SchemaMigrator.class);

    private final JdbcConnectionFactory connectionFactory;
    private final MappingValidator mappingValidator;

    public SchemaMigrator(JdbcConnectionFactory connectionFactory, MappingValidator mappingValidator) {
        this.connectionFactory = connectionFactory;
        this.mappingValidator = mappingValidator;
    }

    /** Outcome of one run: what was created and what already existed. */
    public record Result(List<Table> created, List<Table> skipped) {
    }

    public Result migrate(
            JdbcConnectionConfig source,
            JdbcConnectionConfig target,
            List<Table> sourceTables,
            TableSelection selection) throws ApplierException {
        return migrate(source, target, sourceTables, selection, MigrationLogListener.NONE);
    }

    /**
     * @param logListener told per table, as each one is created or skipped. A table that already
     *     exists is normal on a re-run, so it is reported at {@code INFO} rather than as a problem.
     */
    public Result migrate(
            JdbcConnectionConfig source,
            JdbcConnectionConfig target,
            List<Table> sourceTables,
            TableSelection selection,
            MigrationLogListener logListener) throws ApplierException {

        TableNamingStrategy tableNaming = new MappedTableNamingStrategy(mappingValidator, selection);
        ColumnNamingStrategy columnNaming = new DefaultColumnNamingStrategy();
        DatabaseDialect dialect = dialectFor(target, tableNaming, columnNaming);

        List<Table> created = new ArrayList<>();
        List<Table> skipped = new ArrayList<>();
        try (JdbcConnection src = connectionFactory.open(source);
                JdbcConnection tgt = connectionFactory.open(target)) {
            Connection raw = tgt.unwrap();
            for (Table table : sourceTables) {
                String sourceSchema = table.ref().schema();
                String sourceTable = table.ref().table();
                TableId resolved = resolveTarget(dialect, tableNaming, sourceSchema, sourceTable);
                Table targetTable = toTable(resolved);
                if (dialect.tableExists(raw, resolved)) {
                    skipped.add(targetTable);
                    logListener.emit(
                            MigrationLogLevel.INFO, "table " + resolved + " already exists on target, skipped");
                    continue;
                }
                String ddl = showCreateTable(src, sourceSchema, sourceTable);
                if (ddl == null || ddl.isBlank()) {
                    throw new ApplierException("Target table " + resolved
                            + " is missing and SHOW CREATE TABLE returned empty");
                }
                ConversionResult converted = SQLConverters
                        .create(DatabaseType.MYSQL, DatabaseType.POSTGRESQL,
                                new ConversionConfiguration(tableNaming, columnNaming))
                        .convert(ddl);
                if (converted.getStatus() == ConversionStatus.FAILED) {
                    throw new ApplierException("Failed to convert SHOW CREATE TABLE for "
                            + sourceSchema + "." + sourceTable + ": " + converted.getErrors());
                }
                apply(raw, dialect, resolved, converted.getStatements());
                log.info("created target table {}", resolved);
                created.add(targetTable);
                logListener.emit(MigrationLogLevel.INFO, "created table " + resolved);
            }
        } catch (SQLException ex) {
            throw new ApplierException("Schema migration failed", ex);
        }
        return new Result(List.copyOf(created), List.copyOf(skipped));
    }

    /**
     * Target tables that do not exist yet. Data migration calls this before starting so a missing
     * structure fails immediately instead of surfacing as an insert error mid-snapshot.
     */
    public List<Table> missingTargets(
            JdbcConnectionConfig target, List<Table> sourceTables, TableSelection selection)
            throws ApplierException {

        TableNamingStrategy tableNaming = new MappedTableNamingStrategy(mappingValidator, selection);
        DatabaseDialect dialect = dialectFor(target, tableNaming, new DefaultColumnNamingStrategy());
        List<Table> missing = new ArrayList<>();
        try (JdbcConnection tgt = connectionFactory.open(target)) {
            Connection raw = tgt.unwrap();
            for (Table table : sourceTables) {
                TableId resolved = resolveTarget(
                        dialect, tableNaming, table.ref().schema(), table.ref().table());
                if (!dialect.tableExists(raw, resolved)) {
                    missing.add(toTable(resolved));
                }
            }
        } catch (SQLException ex) {
            throw new ApplierException("Failed to verify target tables", ex);
        }
        return List.copyOf(missing);
    }

    private DatabaseDialect dialectFor(
            JdbcConnectionConfig target, TableNamingStrategy tableNaming, ColumnNamingStrategy columnNaming) {
        ReplayConfig replayConfig = ReplayConfig.builder()
                // Schema migration always operates on an explicitly passed raw connection
                // (tableExists / executeDDL callers), so the dialect never opens its own.
                .connectionProvider(() -> {
                    throw new IllegalStateException(
                            "schema migration dialect never opens its own connection");
                })
                .tableNamingStrategy(tableNaming)
                .columnNamingStrategy(columnNaming)
                .databaseDialectName(ReplayConfig.dialectNameFor(target.getType()))
                .build();
        return DatabaseDialects.create(replayConfig);
    }

    private static TableId resolveTarget(
            DatabaseDialect dialect, TableNamingStrategy tableNaming, String sourceSchema, String sourceTable) {
        TableId mapped = tableNaming.resolveTableId(new TableId(sourceSchema, null, sourceTable));
        if (mapped == null) {
            throw new IllegalStateException(
                    "Table naming returned null for " + sourceSchema + "." + sourceTable);
        }
        return dialect.resolveTableId(mapped);
    }

    private static Table toTable(TableId resolved) {
        return new Table(new TableRef(resolved.getSchema(), resolved.getTable()));
    }

    private static void apply(Connection raw, DatabaseDialect dialect, TableId resolved, List<String> statements)
            throws SQLException {
        ensureSchema(raw, resolved);
        raw.setAutoCommit(false);
        try {
            for (String statement : DdlStatements.executableForPostgres(statements)) {
                dialect.executeDDL(resolved, statement, raw);
            }
            raw.commit();
        } catch (SQLException ex) {
            raw.rollback();
            throw ex;
        } finally {
            raw.setAutoCommit(true);
        }
    }

    private static void ensureSchema(Connection connection, TableId tableId) throws SQLException {
        if (tableId == null || tableId.getSchema() == null || tableId.getSchema().isBlank()) {
            return;
        }
        String schema = tableId.getSchema().replace("\"", "\"\"");
        try (Statement statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA IF NOT EXISTS \"" + schema + "\"");
        }
    }

    private static String showCreateTable(JdbcConnection source, String schema, String table) {
        return source.queryOne(
                "SHOW CREATE TABLE " + quoteMysql(schema) + "." + quoteMysql(table),
                rs -> rs.getString(2));
    }

    private static String quoteMysql(String ident) {
        return "`" + ident.replace("`", "``") + "`";
    }

}
