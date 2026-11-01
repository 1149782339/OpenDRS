package io.opendrs.engine.schema.imports;

import io.opendrs.engine.schema.convert.ConversionResults;
import java.sql.SQLException;

public interface ObjectImporter {

    /**
     * Imports a converted batch. Entries of {@code conversionResults} MUST be in source
     * dependency order — as produced by {@code MigrationEngine.convert} — this importer executes
     * them sequentially as given and does not re-sort. An object whose dependencies are missing
     * fails at execution time and is recorded as failed.
     */
    ImportResults importObjects(ConversionResults conversionResults) throws SQLException;
}
