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
import java.util.Locale;
import java.util.Set;

/**
 * Selected tables that are not InnoDB. A non-transactional engine cannot give a consistent snapshot
 * and its rows arrive out of order through the binlog, so the incremental phase would produce
 * duplicates - hence a blocker rather than a warning.
 */
public final class MysqlEngineCheck implements CheckRule {

    private static final int DETAIL_LIMIT = 10;
    private static final Set<String> SUPPORTED = Set.of("innodb");

    @Override
    public CheckMeta meta() {
        return new CheckMeta(
                CheckIds.SOURCE_UNSUPPORTED_ENGINE,
                "源库是否存在不支持的存储引擎的表",
                CheckGroup.PARAMETER,
                Side.SOURCE);
    }

    @Override
    public Evaluation evaluate(PrecheckContext context, FactStore facts) {
        context.requireTableList();
        List<TableFacts> tables = List.copyOf(facts.source().tableFacts(context.sourceTables()).values());
        List<String> unsupported = tables.stream()
                .filter(table -> table.exists() && table.engine() != null)
                .filter(table -> !SUPPORTED.contains(table.engine().trim().toLowerCase(Locale.ROOT)))
                .map(table -> table.qualifiedName() + "（" + table.engine() + "）")
                .toList();
        if (unsupported.isEmpty()) {
            return new Evaluation(Verdict.PASS, List.of(Finding.note(
                    "所选 " + tables.size() + " 张表均使用 InnoDB")));
        }
        String reason = unsupported.size() + " 张表使用了不支持的存储引擎（共 " + tables.size() + " 张）"
                + (unsupported.size() > DETAIL_LIMIT ? "，仅展示前 " + DETAIL_LIMIT + " 张" : "");
        return Evaluation.fail(
                reason,
                unsupported,
                "转换为 InnoDB 后重新校验",
                DETAIL_LIMIT);
    }
}
