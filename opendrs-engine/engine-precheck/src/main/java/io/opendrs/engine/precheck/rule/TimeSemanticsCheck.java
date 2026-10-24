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
import io.opendrs.engine.precheck.fact.TimezoneInfo;
import java.util.List;

/**
 * Compares the two session time zones by their current UTC offset rather than by name.
 *
 * <p>{@code CST} and {@code Asia/Shanghai} mean the same thing today but not the same thing in
 * general, and a named zone versus {@code +08:00} is a spelling difference, not a defect. The offset
 * is the only thing that decides whether a {@code TIMESTAMP} written to the target lands at the
 * instant it had on the source.
 */
public final class TimeSemanticsCheck implements CheckRule {

    @Override
    public CheckMeta meta() {
        return new CheckMeta(
                CheckIds.COMPAT_TIMEZONE, "源库与目标库的时区是否一致", CheckGroup.PARAMETER, Side.BOTH);
    }

    @Override
    public Evaluation evaluate(PrecheckContext context, FactStore facts) {
        TimezoneInfo source = facts.source().timezone();
        TimezoneInfo target = facts.target().timezone();
        String observed = "源库 " + source.display() + " → 目标库 " + target.display();
        if (source.offsetSeconds() == target.offsetSeconds()) {
            return new Evaluation(Verdict.PASS, List.of(Finding.note(observed)));
        }
        return Evaluation.fail(
                "两端时区偏移不一致，时间列会整体偏移",
                observed,
                "两端 UTC 偏移相同",
                "把源库或目标库的会话时区调成一致（或统一为 UTC）后重新校验");
    }
}
