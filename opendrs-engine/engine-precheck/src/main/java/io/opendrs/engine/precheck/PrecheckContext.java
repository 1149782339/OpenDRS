package io.opendrs.engine.precheck;

import io.opendrs.jdbc.JdbcConnectionConfig;
import io.opendrs.jdbc.metadata.Table;
import io.opendrs.jdbc.metadata.TableRef;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * Immutable per-run input: the two endpoints and the concrete tables the task selected. The lists
 * are expanded by the caller (engine-core) so this module stays free of the mapping types.
 *
 * <p>{@code tablesKnown} is false when the source could not be reached and the selection could only
 * be taken literally (globs and {@code allTables} unresolved). Table-scoped checks must then report
 * "blocked" instead of inspecting an empty list, which would look like a pass.
 *
 * <p>{@code bidirectional} gates the checks that only a two-way topology needs: reverse capture
 * on the target (its binlog/GTID prerequisites) and the server-identity check behind the GTID
 * loop filter. A one-way MySQL → MySQL task skips them.
 */
public record PrecheckContext(
        JdbcConnectionConfig source,
        JdbcConnectionConfig target,
        List<Table> sourceTables,
        List<Table> targetTables,
        boolean tablesKnown,
        boolean bidirectional) {

    public PrecheckContext {
        sourceTables = sourceTables == null ? List.of() : List.copyOf(sourceTables);
        targetTables = targetTables == null ? List.of() : List.copyOf(targetTables);
    }

    /** Source schemas the task touches, falling back to the configured database. */
    public List<String> sourceSchemas() {
        return schemasOf(sourceTables, source);
    }

    /** Target schemas the task writes to, falling back to {@code public} on PostgreSQL. */
    public List<String> targetSchemas() {
        return schemasOf(targetTables, target);
    }

    /**
     * Guard for checks that iterate the table list. Without it an unreachable source would turn the
     * table-level checks into vacuous passes.
     */
    public void requireTableList() {
        if (!tablesKnown) {
            throw new DependencyUnavailableException(
                    CheckIds.SOURCE_CONNECTION, "源库连接失败，无法展开所选表清单");
        }
    }

    private static List<String> schemasOf(List<Table> tables, JdbcConnectionConfig config) {
        LinkedHashSet<String> schemas = new LinkedHashSet<>();
        for (Table table : tables) {
            TableRef ref = table.ref();
            if (ref.schema() != null && !ref.schema().isBlank()) {
                schemas.add(ref.schema());
            }
        }
        if (schemas.isEmpty()) {
            if (config.getType() == io.opendrs.common.metadata.DatabaseType.POSTGRESQL) {
                schemas.add("public");
            } else if (config.getDatabase() != null && !config.getDatabase().isBlank()) {
                schemas.add(config.getDatabase());
            }
        }
        return List.copyOf(schemas);
    }
}
