package io.opendrs.engine.schema.imports;

import io.opendrs.engine.schema.convert.ConversionResults;
import java.sql.SQLException;

public interface ObjectImporter {

    /**
     * Imports a converted batch.
     *
     * <p><b>Order.</b> Entries of {@code conversionResults} MUST already be in source dependency
     * order, as produced by {@code MigrationEngine.convert}. This importer executes them in the
     * given order and never re-sorts: an object whose dependency has not been created yet fails at
     * execution time and is recorded as {@code FAILED} — it is not hoisted, retried or skipped
     * silently.
     *
     * <p><b>Failure.</b> Every object runs in its own transaction, so one failing object does not
     * undo the objects already created. A failed object is recorded in the returned
     * {@link ImportResults} with status {@code FAILED} and the driver's message, and the batch
     * continues with the next object. {@link SQLException} is thrown — with the results collected so
     * far lost — only when the failure is not object-local: a connection-level error (SQLState
     * class {@code 08}) or a rollback that itself failed. Callers that must not continue past the
     * first failure can inspect the returned results and stop on their own.
     *
     * <p><b>Schema.</b> Statements are executed verbatim. An object whose DDL carries no schema
     * qualifier is created in whatever schema the connection currently points at; set
     * {@link ImportConfiguration#getTargetSchema()} for that, or map the schema during conversion.
     *
     * @param conversionResults the converted batch, in dependency order
     * @param configuration batch options, or {@code null} for {@link ImportConfiguration#defaults()}
     */
    ImportResults importObjects(ConversionResults conversionResults, ImportConfiguration configuration)
            throws SQLException;
}
