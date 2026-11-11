package io.opendrs.engine.precheck.rule;

import io.opendrs.engine.common.metadata.DatabaseType;
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
import java.util.List;

/**
 * Whether the target login may switch foreign-key enforcement off for its own session. Full load
 * and replay sessions run with it off (parallel replay may write a child before its parent);
 * MySQL's {@code FOREIGN_KEY_CHECKS} is settable by everyone, PostgreSQL's
 * {@code session_replication_role} needs superuser or, from 15 on,
 * {@code GRANT SET ON PARAMETER session_replication_role}.
 *
 * <p>A behavioural probe (set it, set it back) on the precheck's own session: it has no lasting
 * effect and also covers grants through roles.
 */
public final class TargetForeignKeyBypassCheck implements CheckRule {

    @Override
    public CheckMeta meta() {
        return new CheckMeta(CheckIds.TARGET_FOREIGN_KEY_BYPASS,
                "目标库会话能否关闭外键检查（全量/增量回放）", CheckGroup.PERMISSION, Side.TARGET);
    }

    @Override
    public Evaluation evaluate(PrecheckContext context, FactStore facts) {
        if (facts.target().type() != DatabaseType.POSTGRESQL) {
            return new Evaluation(Verdict.PASS, List.of(Finding.note("FOREIGN_KEY_CHECKS 为会话变量，无需额外权限")));
        }
        try {
            facts.target().connection().execute("SET session_replication_role = replica");
            facts.target().connection().execute("SET session_replication_role = DEFAULT");
            return new Evaluation(Verdict.PASS, List.of(Finding.note("可设置 session_replication_role")));
        } catch (JdbcException | java.sql.SQLException ex) {
            return Evaluation.fail(
                    "目标库账号无法设置 session_replication_role，回放会话无法关闭外键检查："
                            + "外键恢复后，并行回放可能因子表先于父表写入而失败",
                    ex.getMessage(),
                    "超级用户，或 GRANT SET ON PARAMETER session_replication_role（PG15+）",
                    "执行 GRANT SET ON PARAMETER session_replication_role TO <目标库账号> 后重新校验");
        }
    }
}
