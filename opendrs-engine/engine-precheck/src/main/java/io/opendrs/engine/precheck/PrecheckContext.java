package io.opendrs.engine.precheck;

import io.opendrs.engine.jdbc.JdbcConnectionConfig;
import io.opendrs.engine.common.metadata.TableId;
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
        List<TableId> sourceTables,
        List<TableId> targetTables,
        boolean tablesKnown,
        boolean bidirectional,
        /**
         * The task runs a one-way full load followed by incremental replay into PK-only target
         * tables (secondary objects dropped, then restored; FK enforcement off on the target
         * sessions). Gates the checks that only this flow needs.
         */
        boolean deferredObjects) {

    public PrecheckContext {
        sourceTables = sourceTables == null ? List.of() : List.copyOf(sourceTables);
        targetTables = targetTables == null ? List.of() : List.copyOf(targetTables);
    }

    public PrecheckContext(
            JdbcConnectionConfig source,
            JdbcConnectionConfig target,
            List<TableId> sourceTables,
            List<TableId> targetTables,
            boolean tablesKnown,
            boolean bidirectional) {
        this(source, target, sourceTables, targetTables, tablesKnown, bidirectional, false);
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

    private static List<String> schemasOf(List<TableId> tables, JdbcConnectionConfig config) {
        LinkedHashSet<String> schemas = new LinkedHashSet<>();
        for (TableId id : tables) {
            if (id.getSchema() != null && !id.getSchema().isBlank()) {
                schemas.add(id.getSchema());
            }
        }
        if (schemas.isEmpty()) {
            if (config.getType() == io.opendrs.engine.common.metadata.DatabaseType.POSTGRESQL) {
                schemas.add("public");
            } else if (config.getDatabase() != null && !config.getDatabase().isBlank()) {
                schemas.add(config.getDatabase());
            }
        }
        return List.copyOf(schemas);
    }
}
