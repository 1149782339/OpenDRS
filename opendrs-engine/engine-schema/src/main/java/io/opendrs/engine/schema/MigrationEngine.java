package io.opendrs.engine.schema;

import io.opendrs.engine.schema.convert.ConversionConfiguration;
import io.opendrs.engine.schema.convert.ConversionResult;
import io.opendrs.engine.schema.convert.ConversionResults;
import io.opendrs.engine.schema.convert.ConversionStatus;
import io.opendrs.engine.schema.convert.converters.SQLConverter;
import io.opendrs.engine.schema.convert.converters.SQLConverters;
import io.opendrs.engine.schema.dependency.DependencyResolver;
import io.opendrs.engine.schema.export.ExportConfiguration;
import io.opendrs.engine.schema.export.ExportResults;
import io.opendrs.engine.schema.imports.ImportConfiguration;
import io.opendrs.engine.schema.imports.ImportResults;
import io.opendrs.engine.schema.meta.DatabaseObject;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public class MigrationEngine {
    public ExportResults exportObjects(ExportConfiguration config) {
        throw new UnsupportedOperationException("exportObjects is not implemented yet");
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

    public ImportResults importObjects(ConversionResults conversionResults, ImportConfiguration config) {
        throw new UnsupportedOperationException("importObjects is not implemented yet");
    }
}
