package io.opendrs.engine.datacheck;

import io.opendrs.engine.common.log.MigrationLogListener;
import io.opendrs.engine.common.metadata.TableId;
import io.opendrs.engine.jdbc.JdbcConnectionConfig;
import java.util.List;
import java.util.Objects;

/** One data-check run over an aligned list of source/target table pairs. */
public record DataCheckParam(
        JdbcConnectionConfig source,
        JdbcConnectionConfig target,
        List<TableId> sourceTables,
        List<TableId> targetTables,
        boolean includeFloat,
        MigrationLogListener logListener) {

    public DataCheckParam {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(target, "target");
        sourceTables = sourceTables == null ? List.of() : List.copyOf(sourceTables);
        targetTables = targetTables == null ? List.of() : List.copyOf(targetTables);
        if (sourceTables.size() != targetTables.size()) {
            throw new IllegalArgumentException("source and target table lists must be aligned: "
                    + sourceTables.size() + " vs " + targetTables.size());
        }
        logListener = logListener == null ? MigrationLogListener.NONE : logListener;
    }
}
