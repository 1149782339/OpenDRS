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
import java.util.List;

/**
 * Whether the login has the privileges this role needs. The requirement list is passed in by the
 * profile, so the same rule serves either side and the list stays next to the binding that made the
 * check applicable.
 */
public final class PermissionCheck implements CheckRule {

    private final Side side;
    private final List<String> required;

    public PermissionCheck(Side side, List<String> required) {
        this.side = side;
        this.required = List.copyOf(required);
    }

    @Override
    public CheckMeta meta() {
        boolean source = side == Side.SOURCE;
        return new CheckMeta(
                source ? CheckIds.SOURCE_PERMISSION : CheckIds.TARGET_PERMISSION,
                source ? "源库用户权限是否足够" : "目标库用户权限是否足够",
                CheckGroup.PERMISSION,
                side);
    }

    @Override
    public Evaluation evaluate(PrecheckContext context, FactStore facts) {
        List<String> schemas = side == Side.SOURCE ? context.sourceSchemas() : context.targetSchemas();
        List<String> missing = facts.of(side).missingPermissions(schemas, required);
        if (missing.isEmpty()) {
            return new Evaluation(Verdict.PASS, List.of(Finding.note(
                    "已具备 " + String.join("、", required) + "（" + String.join("、", schemas) + "）")));
        }
        return Evaluation.fail(
                "缺少 " + missing.size() + " 项必需权限",
                String.join("、", missing),
                String.join("、", required),
                "为账号授予上述权限（覆盖 " + String.join("、", schemas) + "）后重新校验");
    }
}
