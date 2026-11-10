package io.opendrs.engine.schema;

import io.opendrs.engine.common.metadata.DatabaseType;
import io.opendrs.engine.schema.convert.ConversionConfiguration;
import io.opendrs.engine.schema.convert.ConversionResult;
import io.opendrs.engine.schema.convert.ConversionResults;
import io.opendrs.engine.schema.convert.ConversionStatus;
import io.opendrs.engine.schema.convert.converters.SQLConverter;
import io.opendrs.engine.schema.convert.converters.SQLConverters;
import io.opendrs.engine.schema.dependency.DependencyResolver;
import io.opendrs.engine.schema.dialect.MySqlSchemaDialect;
import io.opendrs.engine.schema.dialect.PostgreSqlSchemaDialect;
import io.opendrs.engine.schema.dialect.SchemaDialect;
import io.opendrs.engine.schema.export.DefaultObjectExporter;
import io.opendrs.engine.schema.export.ExportConfiguration;
import io.opendrs.engine.schema.export.ExportResults;
import io.opendrs.engine.schema.imports.DefaultObjectImporter;
import io.opendrs.engine.schema.imports.ImportConfiguration;
import io.opendrs.engine.schema.imports.ImportResults;
import io.opendrs.engine.schema.meta.DatabaseObject;
import io.opendrs.engine.jdbc.JdbcConnection;
import io.opendrs.engine.jdbc.JdbcConnectionConfig;
import io.opendrs.engine.jdbc.JdbcConnectionFactory;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Schema migration in three stages: {@link #exportObjects} reads the source's DDL,
 * {@link #convert} translates it to the target dialect in dependency order, and
 * {@link #importObjects} executes the result on the target.
 *
 * <p>The stages stay separate on purpose — the batch between them is inspectable (and, in the
 * product, editable) before anything runs against the target.
 */
public class MigrationEngine {

    private final JdbcConnectionFactory connectionFactory;

    public MigrationEngine(JdbcConnectionFactory connectionFactory) {
        this.connectionFactory = Objects.requireNonNull(connectionFactory, "connectionFactory");
    }

    /**
     * Reads every object the selection covers from {@code source}, plus its dependencies.
     *
     * <p>Opens and closes its own connection; the returned {@link ExportResults} carries no
     * connection and can be inspected, or handed to {@link #convert}, after the source is gone.
     */
    public ExportResults exportObjects(JdbcConnectionConfig source, ExportConfiguration configuration)
            throws SQLException {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(configuration, "configuration");
        SchemaDialect dialect = dialectFor(source.getType());
        try (JdbcConnection connection = connectionFactory.open(source)) {
            return new DefaultObjectExporter(connection.connection(), dialect, source.getType())
                    .export(configuration);
        }
    }

    /**
     * Converts every exported object from the source dialect to the target dialect, in source
     * dependency order.
     *
     * <p>Objects whose source DDL never made it out of export are kept in the result with status
     * {@code FAILED} so the batch stays complete; conversion failures land in their per-object
     * {@link ConversionResult}.
     */
    public ConversionResults convert(ExportResults exportResults, ConversionConfiguration config) {
        Objects.requireNonNull(exportResults, "exportResults");
        Objects.requireNonNull(config.getSourceDatabase(), "sourceDatabase");
        Objects.requireNonNull(config.getTargetDatabase(), "targetDatabase");
        SQLConverter converter =
                SQLConverters.create(config.getSourceDatabase(), config.getTargetDatabase(), config);
        List<DatabaseObject> objects = new ArrayList<>();
        if (exportResults.getSchemas() != null) {
            objects.addAll(exportResults.getSchemas());
        }
        if (exportResults.getTables() != null) {
            objects.addAll(exportResults.getTables());
        }
        if (exportResults.getViews() != null) {
            objects.addAll(exportResults.getViews());
        }
        ConversionResults conversionResults = new ConversionResults();
        for (DatabaseObject object : DependencyResolver.topoSort(objects)) {
            conversionResults.put(object, convert(converter, object));
        }
        return conversionResults;
    }

    private static ConversionResult convert(SQLConverter converter, DatabaseObject object) {
        if (object.sourceDdlText() == null || object.sourceDdlText().isBlank()) {
            return ConversionResult.builder()
                    .status(ConversionStatus.FAILED)
                    .statements(List.of())
                    .infos(List.of())
                    .warnings(List.of())
                    .error("no source DDL captured at export")
                    .build();
        }
        return converter.convert(object.sourceDdlText());
    }

    /**
     * Executes a converted batch on {@code target}, in the batch's dependency order.
     *
     * <p>Opens and closes its own connection. Schema remapping is driven by the
     * {@link io.opendrs.engine.common.naming.SchemaNamingStrategy} in {@code configuration}; see
     * {@link ImportConfiguration}.
     */
    public ImportResults importObjects(
            JdbcConnectionConfig target,
            ConversionResults conversionResults,
            ImportConfiguration configuration)
            throws SQLException {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(conversionResults, "conversionResults");
        try (JdbcConnection connection = connectionFactory.open(target)) {
            return new DefaultObjectImporter(connection.connection())
                    .importObjects(conversionResults, configuration);
        }
    }

    private static SchemaDialect dialectFor(DatabaseType type) {
        return switch (type) {
            case MYSQL -> new MySqlSchemaDialect();
            case POSTGRESQL -> new PostgreSqlSchemaDialect();
            case ORACLE -> throw new IllegalArgumentException(
                    "schema migration has no schema dialect for " + type);
        };
    }
}
