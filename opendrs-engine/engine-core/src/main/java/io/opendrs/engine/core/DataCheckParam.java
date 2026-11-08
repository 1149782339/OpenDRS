package io.opendrs.engine.core;

import io.opendrs.engine.common.log.MigrationLogListener;
import io.opendrs.engine.common.metadata.TableId;
import io.opendrs.engine.jdbc.JdbcConnectionConfig;
import java.util.Objects;

/**
 * One data-check run over exactly one table pair. Multi-table orchestration belongs to the service
 * (one engine call per table), so the engine stays a pure per-table function.
 *
 * <p>{@code taskId} is the check task id; it also names the result table
 * {@code datacheck_result_<taskId>}.
 */
public record DataCheckParam(
        long taskId,
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
