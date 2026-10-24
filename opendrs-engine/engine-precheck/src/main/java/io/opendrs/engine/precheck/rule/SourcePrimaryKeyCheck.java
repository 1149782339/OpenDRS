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
 * Tables without a primary key. The incremental phase addresses rows by key, so without one an
 * {@code UPDATE} or {@code DELETE} cannot be matched and the target drifts. A warning rather than a
 * blocker: the migration still runs, the user just has to accept the risk.
 *
 * <p>Only the first {@value #DETAIL_LIMIT} tables are listed - a 400-table schema would otherwise
 * put an unusable wall of text in the dialog - and the message always states the real count.
 */
public final class SourcePrimaryKeyCheck implements CheckRule {

    private static final int DETAIL_LIMIT = 10;

    @Override
    public CheckMeta meta() {
        return new CheckMeta(
                CheckIds.SOURCE_PRIMARY_KEY, "源库无主键表检查", CheckGroup.OBJECT, Side.SOURCE);
    }

    @Override
    public Evaluation evaluate(PrecheckContext context, FactStore facts) {
        context.requireTableList();
        List<TableFacts> tables = List.copyOf(facts.source().tableFacts(context.sourceTables()).values());
        List<String> withoutKey = tables.stream()
                .filter(TableFacts::exists)
                .filter(table -> !table.primaryKey())
                .map(TableFacts::qualifiedName)
                .sorted()
                .toList();
        if (withoutKey.isEmpty()) {
            return new Evaluation(Verdict.PASS, List.of(Finding.note(
                    "所选 " + tables.size() + " 张表均有主键")));
        }
        String reason = withoutKey.size() + " 张表无主键（共 " + tables.size() + " 张），"
                + "增量同步无法唯一定位行"
                + (withoutKey.size() > DETAIL_LIMIT ? "，仅展示前 " + DETAIL_LIMIT + " 张" : "");
        return Evaluation.fail(
                reason,
                withoutKey,
                "为上述表添加主键后重新校验",
                DETAIL_LIMIT);
    }
}
