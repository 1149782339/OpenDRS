package io.opendrs.engine.schema.export;

import io.opendrs.engine.common.metadata.DatabaseType;
import io.opendrs.engine.common.metadata.TableId;
import io.opendrs.engine.common.selection.TableSelection;
import io.opendrs.engine.schema.dialect.SchemaDialect;
import io.opendrs.engine.schema.meta.DatabaseObject;
import io.opendrs.engine.schema.meta.ObjectId;
import io.opendrs.engine.schema.meta.ObjectType;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public class DefaultObjectExporter implements ObjectExporter {

    private final Connection connection;

    private final SchemaDialect dialect;

    /** Recorded on the results so a caller knows which dialect produced the batch; may be null. */
    private final DatabaseType databaseType;

    public DefaultObjectExporter(Connection connection, SchemaDialect dialect) {
        this(connection, dialect, null);
    }

    public DefaultObjectExporter(Connection connection, SchemaDialect dialect, DatabaseType databaseType) {
        this.connection = Objects.requireNonNull(connection, "connection");
        this.dialect = Objects.requireNonNull(dialect, "dialect");
        this.databaseType = databaseType;
    }

    @Override
    public ExportResults export(ExportConfiguration configuration) throws SQLException {
        TableSelection selection = Objects.requireNonNull(configuration.getSelection(), "selection");
        ExportResults results = new ExportResults();
        results.databaseType = databaseType;
        results.schemas = exportSchemas(selection);
        results.tables = exportObjects(selection, ObjectType.TABLE);
        if (configuration.includeViews()) {
            results.views = exportObjects(selection, ObjectType.VIEW);
        }
        attachAndCheckDependencies(results, configuration.includeForeignKeys());
        return results;
    }

    /**
     * One schema object per schema the selection touches. MySQL calls that object a database; the
     * DDL is {@code CREATE DATABASE}, and every table or view exported from it depends on it.
     */
    private List<DatabaseObject> exportSchemas(TableSelection selection) throws SQLException {
        List<DatabaseObject> schemas = new ArrayList<>();
        for (String schema : candidateSchemas(selection)) {
            if (selection.isSchemaExcluded(schema)) {
                continue;
            }
            ObjectId id = ObjectId.schema(schema);
            schemas.add(new DatabaseObject(id, dialect.getObjectDdl(connection, id)));
        }
        return schemas;
    }

    /** Fills each object's dependencies and reports ones missing from this export as errors. */
    private void attachAndCheckDependencies(ExportResults results, boolean includeForeignKeys)
            throws SQLException {
        List<DatabaseObject> exported = new ArrayList<>();
        if (results.schemas != null) {
            exported.addAll(results.schemas);
        }
        if (results.tables != null) {
            exported.addAll(results.tables);
        }
        if (results.views != null) {
            exported.addAll(results.views);
        }
        Set<ObjectId> exportedIds = new HashSet<>();
        for (DatabaseObject object : exported) {
            exportedIds.add(object.id());
        }
        for (DatabaseObject object : exported) {
            List<ObjectId> dependencies = new ArrayList<>();
            if (object.id().type() != ObjectType.SCHEMA) {
                dependencies.add(ObjectId.schema(object.id().schema()));
            }
            dependencies.addAll(
                    dialect.getObjectDependencies(connection, object.id(), !includeForeignKeys));
            object.addDependencies(dependencies);
            for (ObjectId dependency : dependencies) {
                if (!exportedIds.contains(dependency)) {
                    results.message.addErrorMessage(
                            "dependency " + dependency + " of " + object.id() + " was not exported");
                }
            }
        }
    }

    private List<DatabaseObject> exportObjects(TableSelection selection, ObjectType type)
            throws SQLException {
        List<DatabaseObject> objects = new ArrayList<>();
        for (String schema : candidateSchemas(selection)) {
            for (ObjectId id : dialect.getObjects(connection, type, schema)) {
                if (included(selection, id)) {
                    objects.add(new DatabaseObject(id, dialect.getObjectDdl(connection, id)));
                }
            }
        }
        return objects;
    }

    private static List<String> candidateSchemas(TableSelection selection) {
        if (selection.isAll()) {
            throw new IllegalStateException(
                    "selection names no schema or table; the dialect cannot enumerate a whole instance");
        }
        Set<String> schemas = new LinkedHashSet<>(selection.schemas());
        for (TableId table : selection.tables()) {
            schemas.add(table.getSchema());
        }
        return List.copyOf(schemas);
    }

    private static boolean included(TableSelection selection, ObjectId id) {
        if (selection.isSchemaExcluded(id.schema())
                || selection.isTableExcluded(id.schema(), id.name())) {
            return false;
        }
        if (selection.isSchemaLevel()) {
            return true;
        }
        for (TableId table : selection.tables()) {
            if (table.getSchema().equalsIgnoreCase(id.schema())
                    && table.getTable().equalsIgnoreCase(id.name())) {
                return true;
            }
        }
        return false;
    }
}
