package io.opendrs.engine.schema.export;

import io.opendrs.common.metadata.DatabaseType;
import io.opendrs.common.metadata.TableId;
import io.opendrs.common.selection.TableSelection;
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
        results.tables = exportObjects(selection, ObjectType.TABLE);
        if (configuration.includeViews()) {
            results.views = exportObjects(selection, ObjectType.VIEW);
        }
        attachAndCheckDependencies(results, configuration.includeForeignKeys());
        return results;
    }

    /** Fills each object's dependencies and reports ones missing from this export as errors. */
    private void attachAndCheckDependencies(ExportResults results, boolean includeForeignKeys)
            throws SQLException {
        List<DatabaseObject> exported = new ArrayList<>();
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
            List<ObjectId> dependencies =
                    dialect.getObjectDependencies(connection, object.id(), !includeForeignKeys);
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
        if (selection.isSchemaExcluded(id.tableId().getSchema())
                || selection.isTableExcluded(id.tableId().getSchema(), id.tableId().getTable())) {
            return false;
        }
        if (selection.isSchemaLevel()) {
            return true;
        }
        for (TableId table : selection.tables()) {
            if (table.getSchema().equalsIgnoreCase(id.tableId().getSchema())
                    && table.getTable().equalsIgnoreCase(id.tableId().getTable())) {
                return true;
            }
        }
        return false;
    }
}
