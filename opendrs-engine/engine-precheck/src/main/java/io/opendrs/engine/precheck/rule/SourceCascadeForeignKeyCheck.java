package io.opendrs.engine.precheck.rule;

import io.opendrs.engine.common.metadata.TableId;
import io.opendrs.engine.jdbc.JdbcException;
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
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Source foreign keys with referential actions ({@code CASCADE}, {@code SET NULL},
 * {@code SET DEFAULT}) on the selected tables. InnoDB executes those actions inside the storage
 * engine and does not write them to the binlog, and the target sessions run with foreign-key
 * enforcement off, so the target's own cascade does not fire either: rows the source removes or
 * changes by cascade stay as they were on the target.
 */
public final class SourceCascadeForeignKeyCheck implements CheckRule {

    private static final int DETAIL_LIMIT = 10;

    static final String SQL = """
            SELECT CONSTRAINT_SCHEMA, TABLE_NAME, CONSTRAINT_NAME, REFERENCED_TABLE_NAME, UPDATE_RULE, DELETE_RULE
              FROM information_schema.REFERENTIAL_CONSTRAINTS
             WHERE UPDATE_RULE IN ('CASCADE', 'SET NULL', 'SET DEFAULT')
                OR DELETE_RULE IN ('CASCADE', 'SET NULL', 'SET DEFAULT')""";

    @Override
    public CheckMeta meta() {
        return new CheckMeta(CheckIds.SOURCE_CASCADE_FOREIGN_KEY,
                "源库级联外键检查", CheckGroup.OBJECT, Side.SOURCE);
    }

    @Override
    public Evaluation evaluate(PrecheckContext context, FactStore facts) {
        context.requireTableList();
        Set<String> scope = new HashSet<>();
        for (TableId table : context.sourceTables()) {
            scope.add(table.getSchema() + "." + table.getTable());
        }
        List<String> cascading = new ArrayList<>();
        try {
            facts.source().connection().queryList(SQL, rs -> {
                String child = rs.getString(1) + "." + rs.getString(2);
                if (scope.contains(child)) {
                    cascading.add(child + "." + rs.getString(3) + " -> " + rs.getString(4)
                            + " (ON UPDATE " + rs.getString(5) + ", ON DELETE " + rs.getString(6) + ")");
                }
                return null;
            });
        } catch (SQLException ex) {
            throw new JdbcException("读取源库外键失败: " + ex.getMessage(), ex);
        }
        if (cascading.isEmpty()) {
            return new Evaluation(Verdict.PASS, List.of(Finding.note("所选表没有级联外键")));
        }
        String reason = cascading.size() + " 个级联外键：源端级联产生的变更不写 binlog，目标端回放会话关闭了外键，"
                + "级联删除/更新不会同步到目标"
                + (cascading.size() > DETAIL_LIMIT ? "，仅展示前 " + DETAIL_LIMIT + " 个" : "");
        return Evaluation.fail(
                reason,
                cascading.stream().sorted().toList(),
                "确认业务可以接受，或改为由应用显式删除/更新子表",
                DETAIL_LIMIT);
    }
}
