package io.opendrs.debezium;

import io.opendrs.jdbc.JdbcConnection;
import io.opendrs.jdbc.JdbcConnectionFactory;
import io.opendrs.jdbc.metadata.Table;
import io.opendrs.sink.applier.JdbcApplier;
import io.opendrs.sink.ddl.converters.ConversionConfiguration;
import io.opendrs.sink.ddl.converters.ConversionResult;
import io.opendrs.sink.ddl.converters.ConversionStatus;
import io.opendrs.sink.ddl.converters.SQLConverters;
import io.opendrs.sink.dialect.DatabaseType;
import io.opendrs.sink.exception.ApplierException;
import io.opendrs.sink.naming.ColumnNamingStrategy;
import io.opendrs.sink.naming.TableNamingStrategy;
import io.opendrs.sink.relation.TableId;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * After SCHEMA_SNAPSHOT, creates any target tables that Debezium schema-change events did not
 * materialize, using source {@code SHOW CREATE TABLE} + the sink MySQL→PG converter.
 */
public final class TargetSchemaReconciler {

    private static final Logger log = LoggerFactory.getLogger(TargetSchemaReconciler.class);

    private final JdbcConnectionFactory connectionFactory;

    public TargetSchemaReconciler(JdbcConnectionFactory connectionFactory) {
        this.connectionFactory = connectionFactory;
    }

    public void ensureTables(
            DebeziumEngineConfig.EngineSpec spec,
            JdbcApplier applier,
            TableNamingStrategy tableNaming,
            ColumnNamingStrategy columnNaming)
            throws ApplierException {
        if (spec.target() == null || spec.tables() == null || spec.tables().isEmpty()) {
            return;
        }
        try (JdbcConnection source = connectionFactory.open(spec.source());
                JdbcConnection target = connectionFactory.open(spec.target())) {
            Connection targetRaw = target.unwrap();
            for (Table table : spec.tables()) {
                String sourceSchema = table.ref().schema();
                String sourceTable = table.ref().table();
                TableId mapped = tableNaming.resolveTableId(new TableId(sourceSchema, null, sourceTable));
                TableId resolved = applier.dialect().resolveTableId(mapped);
                if (applier.dialect().tableExists(targetRaw, resolved)) {
                    continue;
                }
                String ddl = showCreateTable(source, sourceSchema, sourceTable);
                if (ddl == null || ddl.isBlank()) {
                    throw new ApplierException(
                            "Target table " + resolved + " is missing and SHOW CREATE TABLE returned empty");
                }
                ConversionResult converted = SQLConverters.create(
                                DatabaseType.MYSQL,
                                DatabaseType.POSTGRES,
                                new ConversionConfiguration(tableNaming, columnNaming))
                        .convert(ddl);
                if (converted.getStatus() == ConversionStatus.FAILED) {
                    throw new ApplierException(
                            "Failed to convert SHOW CREATE TABLE for " + sourceSchema + "." + sourceTable
                                    + ": " + converted.getErrors());
                }
                log.info(
                        "SCHEMA_SNAPSHOT fallback CREATE for task {} table {} via SHOW CREATE TABLE",
                        spec.taskId(),
                        resolved);
                applier.applyConvertedDdl(resolved, converted.getStatements());
            }
        } catch (ApplierException ex) {
            throw ex;
        } catch (SQLException ex) {
            throw new ApplierException("Failed to reconcile target schema after snapshot", ex);
        }
    }

    static String showCreateTable(JdbcConnection source, String schema, String table) {
        String qualified = quoteMysql(schema) + "." + quoteMysql(table);
        return source.queryOne("SHOW CREATE TABLE " + qualified, rs -> rs.getString(2));
    }

    private static String quoteMysql(String ident) {
        return "`" + ident.replace("`", "``") + "`";
    }
}
