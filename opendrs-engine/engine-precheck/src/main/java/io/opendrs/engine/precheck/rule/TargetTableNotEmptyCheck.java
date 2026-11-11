package io.opendrs.engine.precheck.rule;

import io.opendrs.engine.common.metadata.TableId;
import io.opendrs.engine.jdbc.JdbcConnection;
import io.opendrs.engine.jdbc.JdbcException;
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
import java.util.ArrayList;
import java.util.List;

/**
 * Target tables that already hold rows. The first full load decides what to do with them by the
 * task's {@code full.targetTablePolicy}: {@code FAIL_IF_NOT_EMPTY} (the default) refuses to start,
 * {@code TRUNCATE} empties them first, {@code APPEND} writes over them (the old behaviour). The
 * check only probes one row per table, so it stays cheap on large tables.
 */
public final class TargetTableNotEmptyCheck implements CheckRule {

    private static final int DETAIL_LIMIT = 10;

    @Override
    public CheckMeta meta() {
        return new CheckMeta(
                CheckIds.TARGET_TABLE_NOT_EMPTY, "目标表是否为空（全量写入策略）", CheckGroup.OBJECT, Side.TARGET);
    }

    @Override
    public Evaluation evaluate(PrecheckContext context, FactStore facts) {
        context.requireTableList();
        List<TableId> existing = facts.target().tableFacts(context.targetTables()).values().stream()
                .filter(TableFacts::exists)
                .map(TableFacts::ref)
                .toList();
        TargetObjectCatalog catalog = TargetObjectCatalog.forType(facts.target().type());
        JdbcConnection connection = facts.target().connection();
        List<String> nonEmpty = new ArrayList<>();
        for (TableId table : existing) {
            try {
                if (connection.queryOne(catalog.probeRowSql(table), rs -> 1) != null) {
                    nonEmpty.add(table.getSchema() + "." + table.getTable());
                }
            } catch (SQLException ex) {
                throw new JdbcException("读取目标表数据失败: " + ex.getMessage(), ex);
            }
        }
        if (nonEmpty.isEmpty()) {
            return new Evaluation(Verdict.PASS, List.of(Finding.note("目标表均为空")));
        }
        String reason = nonEmpty.size() + " 张目标表已有数据。默认策略（FAIL_IF_NOT_EMPTY）下全量启动会失败"
                + (nonEmpty.size() > DETAIL_LIMIT ? "，仅展示前 " + DETAIL_LIMIT + " 张" : "");
        return Evaluation.fail(
                reason,
                nonEmpty.stream().sorted().toList(),
                "先清空这些表，或把目标表写入策略改为 TRUNCATE（启动时清空）/ APPEND（保留并追加写入）",
                DETAIL_LIMIT);
    }
}
