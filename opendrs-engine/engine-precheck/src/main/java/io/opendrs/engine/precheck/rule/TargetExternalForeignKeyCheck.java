package io.opendrs.engine.precheck.rule;

import io.opendrs.engine.common.metadata.TableId;
import io.opendrs.engine.common.metadata.DatabaseType;
import io.opendrs.engine.jdbc.JdbcException;
import io.opendrs.engine.jdbc.deferred.ExternalReference;
import io.opendrs.engine.jdbc.deferred.TargetObjectCatalog;
import io.opendrs.engine.precheck.CheckGroup;
import io.opendrs.engine.precheck.CheckIds;
import io.opendrs.engine.precheck.CheckMeta;
import io.opendrs.engine.precheck.CheckRule;
import io.opendrs.engine.precheck.Evaluation;
import io.opendrs.engine.precheck.Finding;
import io.opendrs.engine.precheck.PrecheckContext;
import io.opendrs.engine.precheck.Side;
import io.opendrs.engine.precheck.Verdict;
import io.opendrs.engine.precheck.fact.FactStore;
import io.opendrs.engine.precheck.fact.TableFacts;
import java.sql.SQLException;
import java.util.List;

/**
 * Foreign keys of target tables <em>outside</em> the task that point at a table inside it. The
 * task never drops them (it does not own the child), so the referenced keys stay in place during
 * the full load, and on PostgreSQL a {@code TRUNCATE} of the parent fails (the task never uses
 * {@code CASCADE}, which would empty the outsider's table too).
 */
public final class TargetExternalForeignKeyCheck implements CheckRule {

    private static final int DETAIL_LIMIT = 10;

    @Override
    public CheckMeta meta() {
        return new CheckMeta(CheckIds.TARGET_EXTERNAL_FOREIGN_KEY,
                "任务外的外键引用任务内的目标表", CheckGroup.OBJECT, Side.TARGET);
    }

    @Override
    public Evaluation evaluate(PrecheckContext context, FactStore facts) {
        context.requireTableList();
        List<TableId> existing = facts.target().tableFacts(context.targetTables()).values().stream()
                .filter(TableFacts::exists)
                .map(TableFacts::ref)
                .toList();
        List<ExternalReference> references;
        try {
            references = TargetObjectCatalog.forType(facts.target().type())
                    .externalReferences(facts.target().connection(), existing);
        } catch (SQLException ex) {
            throw new JdbcException("读取目标库外键失败: " + ex.getMessage(), ex);
        }
        if (references.isEmpty()) {
            return new Evaluation(Verdict.PASS, List.of(Finding.note("没有任务外的外键引用任务内的表")));
        }
        boolean postgres = facts.target().type() == DatabaseType.POSTGRESQL;
        String reason = references.size() + " 个任务外的外键引用了任务内的表：它们不会被删除，"
                + "被引用的主键/唯一键保持原样"
                + (postgres ? "；目标表写入策略为 TRUNCATE 时，被引用的表无法清空" : "")
                + (references.size() > DETAIL_LIMIT ? "，仅展示前 " + DETAIL_LIMIT + " 个" : "");
        return Evaluation.fail(
                reason,
                references.stream().map(ExternalReference::toString).sorted().toList(),
                "把引用方表也加入任务，或确认这些外键可以保留",
                DETAIL_LIMIT);
    }
}
