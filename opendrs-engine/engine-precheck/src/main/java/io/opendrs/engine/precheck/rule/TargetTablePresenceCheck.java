package io.opendrs.engine.precheck.rule;

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
import java.util.List;

/**
 * Bidirectional sync runs no schema migration, so every selected table must already exist on the
 * target. The opposite of {@link TargetTableConflictCheck}, and a blocker rather than a warning:
 * the CDC run fails on the first insert into a missing table either way, this just says so up
 * front.
 */
public final class TargetTablePresenceCheck implements CheckRule {

    private static final int DETAIL_LIMIT = 10;

    @Override
    public CheckMeta meta() {
        return new CheckMeta(
                CheckIds.TARGET_TABLE_PRESENCE, "目标库待同步表存在性检查", CheckGroup.OBJECT, Side.TARGET);
    }

    @Override
    public Evaluation evaluate(PrecheckContext context, FactStore facts) {
        context.requireTableList();
        List<TableFacts> tables = List.copyOf(facts.target().tableFacts(context.targetTables()).values());
        List<String> missing = tables.stream()
                .filter(table -> !table.exists())
                .map(TableFacts::qualifiedName)
                .sorted()
                .toList();
        if (missing.isEmpty()) {
            return new Evaluation(Verdict.PASS, List.of(Finding.note(
                    "目标库 " + tables.size() + " 张待同步表均已存在")));
        }
        return Evaluation.fail(
                missing.size() + " 张目标表不存在，双向同步不做结构迁移"
                        + (missing.size() > DETAIL_LIMIT ? "，仅展示前 " + DETAIL_LIMIT + " 张" : ""),
                missing,
                "先在目标库创建这些表（结构需与源库一致）后重新校验",
                DETAIL_LIMIT);
    }
}
