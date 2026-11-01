package io.opendrs.engine.schema.export;

import java.sql.SQLException;

public interface ObjectExporter {

    ExportResults export(ExportConfiguration configuration) throws SQLException;
}
