package io.opendrs.engine.datacheck;

import io.opendrs.engine.common.log.MigrationLogListener;
import io.opendrs.engine.common.metadata.TableId;
import io.opendrs.engine.jdbc.JdbcConnectionConfig;
import java.util.Objects;
import java.util.function.BiConsumer;

/** One data-check run over exactly one table pair. */
public record DataCheckParam(
        JdbcConnectionConfig source,
        JdbcConnectionConfig target,
        TableId sourceTable,
        TableId targetTable,
        boolean includeFloat,
        MigrationLogListener logListener,
        BiConsumer<TableId, Long> progressListener) {

    public DataCheckParam {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(sourceTable, "sourceTable");
        Objects.requireNonNull(targetTable, "targetTable");
        logListener = logListener == null ? MigrationLogListener.NONE : logListener;
        progressListener = progressListener == null ? (table, rows) -> { } : progressListener;
    }

    public DataCheckParam(
            JdbcConnectionConfig source,
            JdbcConnectionConfig target,
            TableId sourceTable,
            TableId targetTable,
            boolean includeFloat,
            MigrationLogListener logListener) {
        this(source, target, sourceTable, targetTable, includeFloat, logListener, null);
    }
}
