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
import io.opendrs.engine.precheck.fact.InstanceState;
import java.util.List;

/** Whether the target can accept writes: a standby would silently reject every replicated row. */
public final class InstanceStateCheck implements CheckRule {

    @Override
    public CheckMeta meta() {
        return new CheckMeta(
                CheckIds.TARGET_INSTANCE_STATE, "目标实例库状态是否正常", CheckGroup.INSTANCE, Side.TARGET);
    }

    @Override
    public Evaluation evaluate(PrecheckContext context, FactStore facts) {
        InstanceState state = facts.target().instanceState();
        if (state.writable()) {
            return new Evaluation(Verdict.PASS, List.of(Finding.note(state.detail())));
        }
        return Evaluation.fail(
                "目标实例不可写",
                state.detail(),
                "可写的主实例",
                "确认连接的是可写主库而不是只读从库或备库，切换为可写实例后重新校验");
    }
}
