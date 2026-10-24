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
import io.opendrs.jdbc.JdbcException;
import java.util.List;

/**
 * Whether the login may set the session variable {@code gtid_next}. Bidirectional replay tags
 * every applied transaction with {@code SET SESSION gtid_next=...} so the peer capture can drop
 * the write-back (GTID loop prevention); MySQL rejects that statement without one of
 * {@code SESSION_VARIABLES_ADMIN} / {@code SYSTEM_VARIABLES_ADMIN} /
 * {@code REPLICATION_APPLIER} / {@code SUPER}.
 *
 * <p>The probe executes {@code SET SESSION gtid_next='AUTOMATIC'} rather than inspecting
 * {@code mysql.global_grants}: {@code AUTOMATIC} is the default value, so the statement has no
 * side effect and nothing to restore; and a behavioural probe covers grants coming through roles
 * and version differences (8.0.14 introduced {@code SESSION_VARIABLES_ADMIN}), which a privilege
 * table query would miss. A failed connection is not caught here — it surfaces as the
 * connection check's BLOCKED dependency instead.
 */
public final class GtidNextPermissionCheck implements CheckRule {

    /** One of these dynamic/static privileges lets the login set gtid_next. */
    private static final String REQUIRED_PRIVILEGES =
            "SESSION_VARIABLES_ADMIN / SYSTEM_VARIABLES_ADMIN / REPLICATION_APPLIER / SUPER 之一";

    private static final String ADVICE =
            "为账号授予上述权限之一后重新校验（双向同步回放时需用 gtid_next 给事务打标防回环）";

    private final Side side;

    public GtidNextPermissionCheck(Side side) {
        this.side = side;
    }

    @Override
    public CheckMeta meta() {
        boolean source = side == Side.SOURCE;
        return new CheckMeta(
                source ? CheckIds.SOURCE_GTID_NEXT : CheckIds.TARGET_GTID_NEXT,
                source ? "源库用户可否设置 gtid_next（双向回放事务打标）"
                        : "目标库用户可否设置 gtid_next（双向回放事务打标）",
                CheckGroup.PERMISSION,
                side);
    }

    @Override
    public Evaluation evaluate(PrecheckContext context, FactStore facts) {
        try {
            facts.of(side).connection().execute("SET SESSION gtid_next='AUTOMATIC'");
            return new Evaluation(Verdict.PASS, List.of(Finding.note("可设置会话变量 gtid_next")));
        } catch (JdbcException ex) {
            String detail = rootMessage(ex);
            return Evaluation.fail(
                    "无权设置 gtid_next 参数" + (detail.isEmpty() ? "" : "：" + detail),
                    detail.isEmpty() ? "SET SESSION gtid_next 被拒绝" : detail,
                    REQUIRED_PRIVILEGES,
                    ADVICE);
        }
    }

    /** SQLException messages nest (JdbcException wraps the driver's); the innermost is the cause. */
    private static String rootMessage(Throwable ex) {
        Throwable root = ex;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        return root.getMessage() == null ? "" : root.getMessage();
    }
}
