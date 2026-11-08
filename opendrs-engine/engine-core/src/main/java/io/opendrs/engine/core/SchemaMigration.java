package io.opendrs.engine.core;

import io.opendrs.common.log.MigrationLogLevel;
import io.opendrs.common.log.MigrationLogListener;
import io.opendrs.common.metadata.TableId;
import io.opendrs.common.naming.ColumnNamingStrategy;
import io.opendrs.common.naming.SchemaNamingStrategy;
import io.opendrs.common.naming.TableNamingStrategy;
import io.opendrs.common.selection.SchemaMapping;
import io.opendrs.common.selection.TableMappings;
import io.opendrs.common.selection.TableSelection;
import io.opendrs.engine.replay.ReplayConfig;
import io.opendrs.engine.replay.dialect.DatabaseDialect;
import io.opendrs.engine.replay.dialect.DatabaseDialects;
import io.opendrs.engine.replay.mapping.MappingValidator;
import io.opendrs.engine.replay.naming.DefaultColumnNamingStrategy;
import io.opendrs.engine.replay.naming.MappedTableNamingStrategy;
import io.opendrs.engine.schema.SchemaMigrationException;
import io.opendrs.engine.schema.convert.ConversionConfiguration;
import io.opendrs.engine.schema.convert.ConversionResult;
import io.opendrs.engine.schema.convert.ConversionResults;
import io.opendrs.engine.schema.convert.ConversionStatus;
import io.opendrs.engine.schema.export.ExportConfiguration;
import io.opendrs.engine.schema.export.ExportResults;
import io.opendrs.engine.schema.imports.ImportConfiguration;
import io.opendrs.engine.schema.imports.ImportResults;
import io.opendrs.engine.schema.meta.DatabaseObject;
import io.opendrs.engine.schema.meta.ObjectId;
import io.opendrs.engine.schema.meta.ObjectType;
import io.opendrs.jdbc.JdbcConnection;
import io.opendrs.jdbc.JdbcConnectionConfig;
import io.opendrs.jdbc.JdbcConnectionFactory;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * One schema migration, end to end: read the source DDL, convert it to the target dialect in
 * dependency order, then execute the result on the target.
 *
 * <p>Kept out of {@link DefaultMigrationEngine} so it can be driven with a stub batch: the engine
 * only owns the worker thread and the log plumbing.
 *
 * <p>Objects already present on the target are not executed again — they come back as
 * {@code skipped}, so a re-run stays idempotent. A target object that fails to create does not
 * abort the batch: the importer records every outcome and the run fails at the end with the
 * collected failures, which is the only way to see all of them in one pass.
 *
 * <p>Schema remapping happens in two places on purpose. {@code CREATE SCHEMA} is rewritten to the
 * mapped name, because that statement carries its own name. The schema naming strategy also points
 * the target connection at that schema, which is what makes MySQL's unqualified {@code SHOW CREATE
 * TABLE} output land in it. {@code IF NOT EXISTS} is on so a re-run can meet objects already there.
 */
final class SchemaMigration {

    private final JdbcConnectionFactory factory;
    private final io.opendrs.engine.schema.MigrationEngine schemaEngine;
    private final MappingValidator mappingValidator;
    private final TargetDialects targetDialects;

    SchemaMigration(
            JdbcConnectionFactory factory,
            io.opendrs.engine.schema.MigrationEngine schemaEngine,
            MappingValidator mappingValidator) {
        this(factory, schemaEngine, mappingValidator, SchemaMigration::createTargetDialect);
    }

    /** Seam for tests: the target dialect normally comes from the replay module's factory. */
    SchemaMigration(
            JdbcConnectionFactory factory,
            io.opendrs.engine.schema.MigrationEngine schemaEngine,
            MappingValidator mappingValidator,
            TargetDialects targetDialects) {
        this.factory = Objects.requireNonNull(factory, "factory");
        this.schemaEngine = Objects.requireNonNull(schemaEngine, "schemaEngine");
        this.mappingValidator = Objects.requireNonNull(mappingValidator, "mappingValidator");
        this.targetDialects = Objects.requireNonNull(targetDialects, "targetDialects");
    }

    @FunctionalInterface
    interface TargetDialects {
        DatabaseDialect forTarget(
                JdbcConnectionConfig target,
                TableNamingStrategy tableNaming,
                ColumnNamingStrategy columnNaming);
    }

    /**
     * @param tables the selection already expanded against the live source
     */
    SchemaMigrationOutcome run(
            JdbcConnectionConfig source,
            JdbcConnectionConfig target,
            TableSelection selection,
            List<TableId> tables,
            MigrationLogListener listener) throws SchemaMigrationException {
        return run(source, target, selection, tables, listener, ExportConfiguration.builder().build());
    }

    /**
     * Creates whatever target tables are missing and nothing else: primary key only, no unique keys,
     * no secondary indexes, no foreign keys, and no object that already exists is touched.
     *
     * <p>This is what a data migration needs before it starts moving rows — the same
     * export → convert → import pass, with every structural element but the primary key turned off.
     * The primary key stays because the incremental replay addresses rows by it; the rest only cost
     * load time or constrain the order rows can arrive in. Full structure is {@link #run}'s job.
     */
    SchemaMigrationOutcome ensureTables(
            JdbcConnectionConfig source,
            JdbcConnectionConfig target,
            TableSelection selection,
            List<TableId> tables,
            MigrationLogListener listener) throws SchemaMigrationException {
        return run(source, target, selection, tables, listener, ExportConfiguration.builder()
                .includePrimaryKey(true)
                .includeUniqueKeys(false)
                .includeSecondaryIndexes(false)
                .includeForeignKeys(false)
                .build());
    }

    private SchemaMigrationOutcome run(
            JdbcConnectionConfig source,
            JdbcConnectionConfig target,
            TableSelection selection,
            List<TableId> tables,
            MigrationLogListener listener,
            ExportConfiguration structure) throws SchemaMigrationException {
        TableNamingStrategy tableNaming = new MappedTableNamingStrategy(mappingValidator, selection);
        ColumnNamingStrategy columnNaming = new DefaultColumnNamingStrategy();
        SchemaNamingStrategy schemas = schemaNaming(selection);

        // One description of the run drives both stages: export decides what to read, convert decides
        // what to emit. Keeping them apart is how a table-only pass used to leak indexes into the DDL.
        ExportConfiguration exportConfiguration = ExportConfiguration.builder()
                .selection(TableSelection.ofTables(tables))
                .includePrimaryKey(structure.includePrimaryKey())
                .includeUniqueKeys(structure.includeUniqueKeys())
                .includeSecondaryIndexes(structure.includeSecondaryIndexes())
                .includeForeignKeys(structure.includeForeignKeys())
                .build();

        ExportResults exported;
        try {
            exported = schemaEngine.exportObjects(source, exportConfiguration);
        } catch (SQLException ex) {
            throw new SchemaMigrationException("reading source DDL failed: " + ex.getMessage(), ex);
        }
        // An incomplete export would convert a dependency-less batch: stop before anything else.
        List<String> exportErrors = exported.getMessage().getErrorMessages();
        if (!exportErrors.isEmpty()) {
            throw new SchemaMigrationException(
                    "source export is incomplete, nothing was created: " + exportErrors);
        }
        ConversionResults converted = schemaEngine.convert(
                exported,
                ConversionConfiguration.builder(tableNaming, columnNaming)
                        .databases(source.getType(), target.getType())
                        .schemaNamingStrategy(schemas)
                        .ifNotExists(true)
                        .includePrimaryKey(structure.includePrimaryKey())
                        .includeUniqueKeys(structure.includeUniqueKeys())
                        .includeSecondaryIndexes(structure.includeSecondaryIndexes())
                        .includeForeignKeys(structure.includeForeignKeys())
                        .build());

        List<ObjectId> existing = existingTargets(target, converted, tableNaming, columnNaming, schemas);
        List<TableId> skipped = new ArrayList<>();
        for (ObjectId id : existing) {
            if (id.type() == ObjectType.SCHEMA) {
                listener.emit(
                        MigrationLogLevel.INFO,
                        "target schema already exists, skipped: " + schemas.resolveSchemaName(id.schema()));
                continue;
            }
            TableId tableId = targetTable(tableNaming, id);
            skipped.add(tableId);
            listener.emit(
                    MigrationLogLevel.INFO, "target table already exists, skipped: " + ref(tableId));
        }
        ConversionResults pending = excluding(converted, existing);

        ImportResults imported;
        try {
            imported = schemaEngine.importObjects(
                    target,
                    pending,
                    ImportConfiguration.builder()
                            .schemaNamingStrategy(schemaNaming(selection))
                            .listener((id, result) -> listener.emit(
                                    levelOf(result.getStatus()), describe(id, result)))
                            .build());
        } catch (SQLException ex) {
            throw new SchemaMigrationException("creating target objects failed: " + ex.getMessage(), ex);
        }

        List<TableId> created = new ArrayList<>();
        List<String> failures = new ArrayList<>();
        for (Map.Entry<ObjectId, ConversionResult> entry : imported.entries()) {
            ConversionStatus status = entry.getValue().getStatus();
            if (status == ConversionStatus.SUCCEEDED && entry.getKey().type() != ObjectType.SCHEMA) {
                created.add(targetTable(tableNaming, entry.getKey()));
            } else if (status == ConversionStatus.FAILED) {
                failures.add(entry.getKey() + " — " + entry.getValue().getErrors());
            }
        }
        if (!failures.isEmpty()) {
            throw new SchemaMigrationException(failures.size() + " of " + pending.size()
                    + " object(s) could not be created on the target: " + failures);
        }
        return new SchemaMigrationOutcome(List.copyOf(created), List.copyOf(skipped));
    }

    /** Target tables the selection expects but the target does not have yet. */
    List<TableId> missingTargets(
            JdbcConnectionConfig target, List<TableId> sourceTables, TableSelection selection)
            throws SchemaMigrationException {
        TableNamingStrategy tableNaming = new MappedTableNamingStrategy(mappingValidator, selection);
        DatabaseDialect dialect =
                targetDialects.forTarget(target, tableNaming, new DefaultColumnNamingStrategy());
        List<TableId> missing = new ArrayList<>();
        try (JdbcConnection connection = factory.open(target)) {
            Connection raw = connection.connection();
            for (TableId tableId : sourceTables) {
                TableId targetId = tableNaming.resolveTableId(tableId);
                if (!dialect.tableExists(raw, targetId)) {
                    missing.add(TableId.of(targetId.getSchema(), targetId.getTable()));
                }
            }
        } catch (SQLException ex) {
            throw new SchemaMigrationException("checking target tables failed: " + ex.getMessage(), ex);
        }
        return missing;
    }

    /** Source objects of this run whose target counterpart already exists. */
    private List<ObjectId> existingTargets(
            JdbcConnectionConfig target,
            ConversionResults converted,
            TableNamingStrategy tableNaming,
            ColumnNamingStrategy columnNaming,
            SchemaNamingStrategy schemas) throws SchemaMigrationException {
        DatabaseDialect dialect = targetDialects.forTarget(target, tableNaming, columnNaming);
        List<ObjectId> existing = new ArrayList<>();
        try (JdbcConnection connection = factory.open(target)) {
            Connection raw = connection.connection();
            for (Map.Entry<DatabaseObject, ConversionResult> entry : converted.entries()) {
                ObjectId id = entry.getKey().id();
                boolean present = id.type() == ObjectType.SCHEMA
                        ? schemaExists(raw, schemas.resolveSchemaName(id.schema()))
                        : dialect.tableExists(raw, tableNaming.resolveTableId(id.tableId()));
                if (present) {
                    existing.add(id);
                }
            }
        } catch (SQLException ex) {
            throw new SchemaMigrationException(
                    "checking which target tables already exist failed: " + ex.getMessage(), ex);
        }
        return existing;
    }

    /** {@code information_schema.schemata} names the schema on both PostgreSQL and MySQL. */
    private static boolean schemaExists(Connection connection, String schema) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT 1 FROM information_schema.schemata WHERE schema_name = ?")) {
            statement.setString(1, schema);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next();
            }
        }
    }

    private static DatabaseDialect createTargetDialect(
            JdbcConnectionConfig target,
            TableNamingStrategy tableNaming,
            ColumnNamingStrategy columnNaming) {
        ReplayConfig replayConfig = ReplayConfig.builder()
                // Schema migration always passes its own raw connection, so the dialect never
                // opens its own.
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

    /**
     * Source schema onto target schema, as configured on the selection. Unmapped schemas keep their
     * name, which is what a plain MySQL-database to PG-schema migration wants.
     */
    private static SchemaNamingStrategy schemaNaming(TableSelection selection) {
        TableMappings mappings = selection.mappings();
        return schema -> {
            if (mappings == null || mappings.schema() == null) {
                return schema;
            }
            for (SchemaMapping mapping : mappings.schema()) {
                if (schema.equals(mapping.source())) {
                    return mapping.target();
                }
            }
            return schema;
        };
    }

    private static ConversionResults excluding(ConversionResults converted, List<ObjectId> excluded) {
        ConversionResults pending = new ConversionResults();
        for (Map.Entry<DatabaseObject, ConversionResult> entry : converted.entries()) {
            if (!excluded.contains(entry.getKey().id())) {
                pending.put(entry.getKey(), entry.getValue());
            }
        }
        return pending;
    }

    private static TableId targetTable(TableNamingStrategy tableNaming, ObjectId id) {
        TableId target = tableNaming.resolveTableId(id.tableId());
        return TableId.of(target.getSchema(), target.getTable());
    }

    private static String ref(TableId tableId) {
        return tableId.getSchema() + "." + tableId.getTable();
    }

    private static MigrationLogLevel levelOf(ConversionStatus status) {
        return status == ConversionStatus.FAILED ? MigrationLogLevel.ERROR : MigrationLogLevel.INFO;
    }

    private static String describe(ObjectId id, ConversionResult result) {
        String name = id.type() == ObjectType.SCHEMA ? id.schema() : id.schema() + "." + id.name();
        if (result.getStatus() == ConversionStatus.SUCCEEDED) {
            return "created " + name;
        }
        if (result.getStatus() == ConversionStatus.FAILED) {
            return "failed to create " + name + ": " + result.getErrors();
        }
        return "no target DDL, dropped " + name;
    }
}
