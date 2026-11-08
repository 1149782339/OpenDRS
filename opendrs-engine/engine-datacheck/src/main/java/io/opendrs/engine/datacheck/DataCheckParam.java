package io.opendrs.engine.datacheck;

import io.opendrs.engine.common.log.MigrationLogListener;
import io.opendrs.engine.common.metadata.TableId;
import io.opendrs.engine.jdbc.JdbcConnectionConfig;
import java.util.Objects;

/** One data-check run over exactly one table pair. */
public record DataCheckParam(
        JdbcConnectionConfig source,
        JdbcConnectionConfig target,
        TableId sourceTable,
        TableId targetTable,
        boolean includeFloat,
        MigrationLogListener logListener) {

    public DataCheckParam {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(sourceTable, "sourceTable");
        Objects.requireNonNull(targetTable, "targetTable");
        logListener = logListener == null ? MigrationLogListener.NONE : logListener;
    }
}
