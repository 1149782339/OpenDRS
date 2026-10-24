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
 * Target tables that already exist. Schema migration skips them, so the run does not destroy data -
 * but the rows will be merged into whatever structure is already there, which is exactly the kind of
 * thing the user should confirm before starting.
 */
public final class TargetTableConflictCheck implements CheckRule {

    private static final int DETAIL_LIMIT = 10;

    @Override
    public CheckMeta meta() {
        return new CheckMeta(
                CheckIds.TARGET_TABLE_CONFLICT, "目标库同名表检查", CheckGroup.OBJECT, Side.TARGET);
    }

    @Override
    public Evaluation evaluate(PrecheckContext context, FactStore facts) {
        context.requireTableList();
        List<TableFacts> tables = List.copyOf(facts.target().tableFacts(context.targetTables()).values());
        List<String> existing = tables.stream()
                .filter(TableFacts::exists)
                .map(TableFacts::qualifiedName)
                .sorted()
                .toList();
        if (existing.isEmpty()) {
            return new Evaluation(Verdict.PASS, List.of(Finding.note(
                    "目标库不存在同名表，结构迁移将新建 " + tables.size() + " 张表")));
        }
        String reason = existing.size() + " 张目标表已存在，结构迁移会跳过它们"
                + (existing.size() > DETAIL_LIMIT ? "，仅展示前 " + DETAIL_LIMIT + " 张" : "");
        return Evaluation.fail(
                reason,
                existing,
                "确认可以写入这些已有表，或先清理目标库后重新校验",
                DETAIL_LIMIT);
    }
}
