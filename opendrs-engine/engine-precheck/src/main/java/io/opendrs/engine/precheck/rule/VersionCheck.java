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
import io.opendrs.engine.precheck.fact.ServerVersion;
import java.util.List;

/** Whether the endpoint's version is in the range this product supports. */
public final class VersionCheck implements CheckRule {

    private final Side side;
    private final int requiredMajor;
    private final int requiredMinor;

    public VersionCheck(Side side, int requiredMajor, int requiredMinor) {
        this.side = side;
        this.requiredMajor = requiredMajor;
        this.requiredMinor = requiredMinor;
    }

    @Override
    public CheckMeta meta() {
        boolean source = side == Side.SOURCE;
        return new CheckMeta(
                source ? CheckIds.SOURCE_VERSION : CheckIds.TARGET_VERSION,
                source ? "源库版本是否支持" : "目标库版本是否支持",
                CheckGroup.VERSION,
                side);
    }

    @Override
    public Evaluation evaluate(PrecheckContext context, FactStore facts) {
        ServerVersion version = facts.of(side).version();
        if (version.atLeast(requiredMajor, requiredMinor)) {
            return new Evaluation(Verdict.PASS, List.of(Finding.note(version.raw())));
        }
        return Evaluation.fail(
                "版本过低，不支持迁移",
                version.raw(),
                requiredMajor + "." + requiredMinor + " 及以上",
                "升级到 " + requiredMajor + "." + requiredMinor + " 及以上版本后重新校验");
    }
}
