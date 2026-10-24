package io.opendrs.engine.precheck.rule;

import io.opendrs.engine.precheck.CheckGroup;
import io.opendrs.engine.precheck.CheckMeta;
import io.opendrs.engine.precheck.CheckRule;
import io.opendrs.engine.precheck.Evaluation;
import io.opendrs.engine.precheck.Finding;
import io.opendrs.engine.precheck.PrecheckContext;
import io.opendrs.engine.precheck.Side;
import io.opendrs.engine.precheck.Verdict;
import io.opendrs.engine.precheck.fact.FactStore;
import java.util.List;
import java.util.Locale;
import java.util.function.Predicate;

/**
 * One MySQL server parameter. Every parameter check is an instance of this class with a different
 * (name, expectation) pair, so a new parameter is a line in the profile rather than a new class.
 *
 * <p>{@link Absent} is the part that cannot be guessed: {@code log_bin} missing means the server is
 * misconfigured, while {@code log_slave_updates} missing on 8.4 simply means the parameter was
 * removed - the first is a failure, the second is "does not apply".
 */
public final class MysqlParameterCheck implements CheckRule {

    public enum Absent {
        /** The parameter must exist; its absence is a failure. */
        FAIL,
        /** The parameter was removed in this version; absence is neither a pass nor a failure. */
        NOT_APPLICABLE
    }

    private final CheckMeta meta;
    private final String parameter;
    private final String expected;
    private final Predicate<String> accepted;
    private final Absent absent;
    private final String advice;
    private final Side side;

    public MysqlParameterCheck(
            String id,
            String title,
            String parameter,
            String expected,
            Predicate<String> accepted,
            Absent absent,
            String advice) {
        this(id, title, parameter, expected, accepted, absent, advice, Side.SOURCE);
    }

    /**
     * @param side which endpoint to inspect — bidirectional sync checks the same MySQL parameters
     *     on the target, because there the target is a capture source too
     */
    public MysqlParameterCheck(
            String id,
            String title,
            String parameter,
            String expected,
            Predicate<String> accepted,
            Absent absent,
            String advice,
            Side side) {
        this.meta = new CheckMeta(id, title, CheckGroup.PARAMETER, side);
        this.parameter = parameter;
        this.expected = expected;
        this.accepted = accepted;
        this.absent = absent;
        this.advice = advice;
        this.side = side;
    }

    @Override
    public CheckMeta meta() {
        return meta;
    }

    @Override
    public Evaluation evaluate(PrecheckContext context, FactStore facts) {
        String value = facts.of(side).parameters().get(parameter);
        if (value == null) {
            if (absent == Absent.NOT_APPLICABLE) {
                return Evaluation.notApplicable(parameter + " 在当前版本不存在，无需设置");
            }
            return Evaluation.fail(
                    parameter + " 未设置", "参数不存在", expected, advice);
        }
        if (accepted.test(value)) {
            return new Evaluation(Verdict.PASS, List.of(Finding.note(parameter + " = " + value)));
        }
        return Evaluation.fail(parameter + " 取值不符合要求", value, expected, advice);
    }

    /** MySQL spells booleans as {@code ON}/{@code OFF}, {@code 1}/{@code 0} or a Java boolean. */
    public static boolean isOn(String value) {
        if (value == null) {
            return false;
        }
        String text = value.trim().toLowerCase(Locale.ROOT);
        return "on".equals(text) || "true".equals(text) || "1".equals(text);
    }

    public static Predicate<String> equalTo(String expected) {
        return value -> value != null && value.trim().equalsIgnoreCase(expected);
    }
}
