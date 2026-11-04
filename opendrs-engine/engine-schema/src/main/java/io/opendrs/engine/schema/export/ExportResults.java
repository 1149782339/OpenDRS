package io.opendrs.engine.schema.export;

import io.opendrs.common.metadata.DatabaseType;
import io.opendrs.engine.schema.meta.DatabaseObject;

import java.util.List;

public class ExportResults {
    List<DatabaseObject> schemas;
    List<DatabaseObject> tables;
    List<DatabaseObject> views;
    List<DatabaseObject>  sequences;
    /** Never null — exporters report progress and problems through it. */
    final ExportMessage message = new ExportMessage();
    DatabaseType databaseType;

    public List<DatabaseObject> getSchemas() {
        return schemas;
    }

    public List<DatabaseObject> getTables() {
        return tables;
    }

    public List<DatabaseObject> getViews() {
        return views;
    }

    public List<DatabaseObject> getSequences() {
        return sequences;
    }

    public ExportMessage getMessage() {
        return message;
    }

    public DatabaseType getDatabaseType() {
        return databaseType;
    }
}
