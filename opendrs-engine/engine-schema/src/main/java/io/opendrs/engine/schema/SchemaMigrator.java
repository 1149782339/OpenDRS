package io.opendrs.engine.schema;

import io.opendrs.common.log.MigrationLogListener;
import io.opendrs.jdbc.JdbcConnectionConfig;
import io.opendrs.jdbc.JdbcConnectionFactory;
import io.opendrs.jdbc.metadata.Table;
import java.util.List;

/**
 * Temporary stand-in, kept only so existing callers still compile.
 *
 * <p>The previous single-table implementation was removed on purpose: schema migration will be
 * rebuilt on top of the export → convert → import framework in this module, and that framework is
 * not ready yet. Single-table migration is just the degenerate case of it (an export batch of one
 * table), so it will fold into the framework rather than stay a separate code path.
 *
 * <p>Until then, every entry point here throws {@link UnsupportedOperationException}: the old
 * data-migration path ({code migrateSchema}) does not work.
 */
public final class SchemaMigrator {

    public SchemaMigrator(JdbcConnectionFactory connectionFactory) {
    }

    /** Outcome of one run: what was created and what already existed. */
    public record Result(List<Table> created, List<Table> skipped) {
    }

    public Result migrate(
            JdbcConnectionConfig source,
            JdbcConnectionConfig target,
            List<Table> sourceTables,
            SchemaTarget schemaTarget) throws SchemaMigrationException {
        throw new UnsupportedOperationException("SchemaMigrator is being rebuilt on the export/convert/import framework");
    }

    public Result migrate(
            JdbcConnectionConfig source,
            JdbcConnectionConfig target,
            List<Table> sourceTables,
            SchemaTarget schemaTarget,
            MigrationLogListener logListener) throws SchemaMigrationException {
        throw new UnsupportedOperationException("SchemaMigrator is being rebuilt on the export/convert/import framework");
    }

    public List<Table> missingTargets(
            JdbcConnectionConfig target, List<Table> sourceTables, SchemaTarget schemaTarget)
            throws SchemaMigrationException {
        throw new UnsupportedOperationException("SchemaMigrator is being rebuilt on the export/convert/import framework");
    }
}
