package io.opendrs.migration.service;

import io.opendrs.common.log.MigrationLogLevel;
import io.opendrs.common.metadata.TableId;
import io.opendrs.engine.core.TableSelectionExpander;
import io.opendrs.engine.replay.mapping.MappingValidator;
import io.opendrs.engine.replay.mapping.TableSelection;
import io.opendrs.engine.replay.sql.ExpressionBuilder;
import io.opendrs.engine.replay.sql.IdentifierRule;
import io.opendrs.jdbc.JdbcConnection;
import io.opendrs.jdbc.JdbcConnectionFactory;
import io.opendrs.jdbc.metadata.TableRef;
import io.opendrs.migration.domain.ConnectionInfo;
import io.opendrs.migration.mapper.FullLoadCheckpointMapper;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Resume-by-table decision for a crashed full load, made on the coordinator thread before the
 * data engine starts. Reads the completed tables one pipeline recorded through
 * {@link JdbcFullLoadCheckpointListener} ({@code task_snapshot_complete_tables}, presence is the
 * done mark) and answers: which source {@code db.table}s still owe a snapshot — the current
 * selection minus the recorded ones — so the caller can hand them to the engine as
 * {@code resumeTables}.
 *
 * <p>The half-finished tables are part of the remaining set — a table is either done or it is not
 * — so they are re-exported together with the never-started ones. Their partial rows are wiped on
 * the target first (TRUNCATE); 迁移期间目标表禁用外键，TRUNCATE 无需考虑依赖顺序。
 *
 * <p>Degradation is deliberate: a TRUNCATE that fails (or a target name that cannot be mapped)
 * only logs a warning. The resume then re-inserts over the partial rows and the applier's
 * dup-key → upsert fallback digests the duplicates — the checkpoint mechanism never turns a
 * recoverable full load into a failed task.
 *
 * <p>Every decision is narrated through {@link MigrationLogService}'s per-task listener, so the
 * resume reasoning is visible in the task log (frontend), not only in the backend's slf4j stream.
 */
@Service
public class FullLoadResumePlanner {

    private static final Logger log = LoggerFactory.getLogger(FullLoadResumePlanner.class);

    private final FullLoadCheckpointMapper checkpointMapper;
    private final MappingValidator mappingValidator;
    private final JdbcConnectionFactory connectionFactory;
    private final TableSelectionExpander expander;
    private final MigrationLogService logService;

    public FullLoadResumePlanner(
            FullLoadCheckpointMapper checkpointMapper,
            MappingValidator mappingValidator,
            JdbcConnectionFactory connectionFactory,
            MigrationLogService logService) {
        this.checkpointMapper = checkpointMapper;
        this.mappingValidator = mappingValidator;
        this.connectionFactory = connectionFactory;
        this.expander = new TableSelectionExpander(connectionFactory);
        this.logService = logService;
    }

    /**
     * @param taskId only for narrating the resume decisions into the task log
     * @param pipelineId the directional pipeline the checkpoints are recorded for
     * @return the remaining tables to re-snapshot (possibly empty when everything is done), or
     *     {@code null} when there is nothing to resume — no completed table has been recorded,
     *     or the selection could not be expanded
     */
    public Set<String> resumeTables(
            long taskId,
            long pipelineId,
            TableSelection selection,
            ConnectionInfo source,
            ConnectionInfo target) {
        Set<String> done = checkpointMapper.findDoneTables(pipelineId);
        if (done.isEmpty()) {
            // Fresh pipeline (or a run that crashed before completing any table): nothing to
            // resume — the caller runs a normal full load, and not even the source-side
            // expansion below is needed.
            return null;
        }
        for (String table : done) {
            log.info("table \"{}\" already done, skipped", table);
            logService.listenerFor(taskId).emit(MigrationLogLevel.INFO,
                    "table \"" + table + "\" already done, skipped");
        }
        // 剩余表 = 当前选择 − 已完成。不信任上次运行时存下的清单：重新展开源端表列表，
        // 两次运行之间源库的增删会如实反映，而不是跟着过期的快照清单走。
        List<String> all = expandSelection(taskId, source, selection);
        if (all.isEmpty()) {
            log.warn("Task {} pipeline {} could not expand the selection to resume from,"
                    + " falling back to a full re-snapshot", taskId, pipelineId);
            logService.listenerFor(taskId).emit(MigrationLogLevel.WARNING,
                    "could not expand the table selection to resume from,"
                            + " falling back to a full re-snapshot");
            return null;
        }
        List<String> remaining = remaining(all, done);
        truncateRemaining(taskId, target, selection, remaining);
        return new LinkedHashSet<>(remaining);
    }

    /** 全量清单 − done，保持清单顺序，便于日志与测试稳定。 */
    static List<String> remaining(List<String> allTables, Set<String> done) {
        return allTables.stream().filter(table -> !done.contains(table)).toList();
    }

    private List<String> expandSelection(long taskId, ConnectionInfo source, TableSelection selection) {
        try {
            return expander.expand(source.toJdbcConfig(), selection).stream()
                    .map(table -> table.ref().schema() + "." + table.ref().table())
                    .toList();
        } catch (RuntimeException ex) {
            log.warn("Task {} could not expand the task selection for a resume table list: {}",
                    taskId, ex.getMessage());
            return List.of();
        }
    }

    /**
     * 对每张未完成表在目标库 TRUNCATE。checkpoint 存的是源端 {@code db.table}，这里走
     * MappingValidator.resolveRef 的同一链路换算目标端表名（MappedTableNamingStrategy 在
     * sink 边界用的就是它），方言差异（MySQL 反引号 / PG 双引号）交给 JDBC 元数据的
     * identifier quote + engine-replay 的 ExpressionBuilder，不手写两套 SQL。
     */
    private void truncateRemaining(long taskId, ConnectionInfo target, TableSelection selection,
                                   List<String> remaining) {
        if (remaining.isEmpty()) {
            return;
        }
        JdbcConnection connection = openTarget(taskId, target);
        if (connection == null) {
            return;
        }
        try (connection) {
            IdentifierRule rule = identifierRuleOf(connection);
            for (String sourceTable : remaining) {
                int dot = sourceTable.indexOf('.');
                if (dot <= 0 || dot == sourceTable.length() - 1) {
                    log.warn("checkpoint table \"{}\" is not a db.table pair, skip truncate", sourceTable);
                    logService.listenerFor(taskId).emit(MigrationLogLevel.WARNING,
                            "checkpoint table \"" + sourceTable
                                    + "\" is not a db.table pair, truncate skipped, upsert fallback applies");
                    continue;
                }
                TableRef mapped = mappingValidator.resolveRef(
                        selection, sourceTable.substring(0, dot), sourceTable.substring(dot + 1));
                String sql = new ExpressionBuilder(rule)
                        .append("truncate table ")
                        .append(TableId.of(mapped.schema(), mapped.table()))
                        .toString();
                try {
                    connection.execute(sql);
                    log.info("truncate target table {} for resume", sql.substring("truncate table ".length()));
                    logService.listenerFor(taskId).emit(MigrationLogLevel.INFO,
                            "truncate target table " + sql.substring("truncate table ".length())
                                    + " for resume");
                } catch (RuntimeException ex) {
                    log.warn("truncate failed (\"{}\"), resume relies on the upsert fallback: {}",
                            sql, ex.getMessage());
                    logService.listenerFor(taskId).emit(MigrationLogLevel.WARNING,
                            "truncate failed for \"" + sql + "\", resume relies on the upsert fallback: "
                                    + ex.getMessage());
                }
            }
        }
    }

    private JdbcConnection openTarget(long taskId, ConnectionInfo target) {
        try {
            return connectionFactory.open(target.toJdbcConfig());
        } catch (RuntimeException ex) {
            log.warn("cannot connect to the target to truncate half-loaded tables,"
                    + " resume relies on the upsert fallback: {}", ex.getMessage());
            logService.listenerFor(taskId).emit(MigrationLogLevel.WARNING,
                    "cannot connect to the target to truncate half-loaded tables,"
                            + " resume relies on the upsert fallback: " + ex.getMessage());
            return null;
        }
    }

    private static IdentifierRule identifierRuleOf(JdbcConnection connection) {
        String quote = "\"";
        try {
            String fromMetadata = connection.unwrap().getMetaData().getIdentifierQuoteString();
            if (fromMetadata != null && !fromMetadata.isBlank()) {
                quote = fromMetadata;
            }
        } catch (RuntimeException | java.sql.SQLException ex) {
            log.warn("identifier quote lookup failed, falling back to double quotes: {}", ex.getMessage());
        }
        return new IdentifierRule(".", quote);
    }
}
