package io.opendrs.engine.precheck;

import java.util.List;

/**
 * One row of the wizard's checklist. Created as {@link ExecutionState#PENDING} when the plan is
 * published, then rewritten in place by the runner - the row never appears or disappears, so the
 * user sees every check spin from the start and turn green one by one.
 */
public record PrecheckItem(
        String id,
        CheckGroup group,
        String title,
        Side side,
        int order,
        ExecutionState state,
        Verdict verdict,
        Severity severity,
        String message,
        String actual,
        String expected,
        String advice,
        List<String> details) {

    public PrecheckItem {
        details = details == null ? List.of() : List.copyOf(details);
    }

    public static PrecheckItem pending(CheckMeta meta, Severity severity, int order) {
        return new PrecheckItem(meta.id(), meta.group(), meta.title(), meta.side(), order,
                ExecutionState.PENDING, Verdict.UNKNOWN, severity, null, null, null, null, List.of());
    }

    public PrecheckItem running() {
        return new PrecheckItem(id, group, title, side, order, ExecutionState.RUNNING, Verdict.UNKNOWN,
                severity, message, actual, expected, advice, details);
    }

    public PrecheckItem completed(Evaluation evaluation) {
        Finding primary = evaluation.primary();
        return new PrecheckItem(id, group, title, side, order, ExecutionState.COMPLETED,
                evaluation.verdict(), severity,
                primary == null ? null : primary.reason(),
                primary == null ? null : primary.actual(),
                primary == null ? null : primary.expected(),
                primary == null ? null : primary.advice(),
                evaluation.details());
    }

    /**
     * A row that could not be evaluated because the check it depends on already failed. The reason
     * names that check by title - nine copies of the same JDBC error is noise, not information - and
     * the underlying error is kept as evidence in {@code actual}.
     */
    public PrecheckItem blocked(String rootCauseTitle, String detail) {
        return new PrecheckItem(id, group, title, side, order, ExecutionState.BLOCKED, Verdict.UNKNOWN,
                severity,
                "依赖的「" + rootCauseTitle + "」未通过，无法读取该项",
                detail, null,
                "先修复「" + rootCauseTitle + "」后重新校验", List.of());
    }

    public PrecheckItem error(String reason) {
        return new PrecheckItem(id, group, title, side, order, ExecutionState.ERROR, Verdict.UNKNOWN,
                severity, reason, null, null, "检查执行异常，请查看服务端日志后重新预检查", List.of());
    }

    /** Whether this row blocks the migration (a failed blocker, or anything without a verdict). */
    public boolean blocking() {
        if (state == ExecutionState.COMPLETED) {
            return verdict == Verdict.UNKNOWN || (severity == Severity.BLOCKER && verdict == Verdict.FAIL);
        }
        return true;
    }
}
