package io.opendrs.engine.precheck;

import java.util.ArrayList;
import java.util.List;

/** What a {@link CheckRule} decided, plus the evidence for it. */
public record Evaluation(Verdict verdict, List<Finding> findings) {

    public Evaluation {
        findings = findings == null ? List.of() : List.copyOf(findings);
    }

    public static Evaluation pass() {
        return new Evaluation(Verdict.PASS, List.of());
    }

    public static Evaluation fail(String reason, String actual, String expected, String advice) {
        return new Evaluation(Verdict.FAIL, List.of(Finding.of(reason, actual, expected, advice)));
    }

    /** Failure with per-object detail lines (first {@code limit} of them). */
    public static Evaluation fail(String reason, List<String> details, String advice, int limit) {
        List<Finding> findings = new ArrayList<>();
        findings.add(Finding.of(reason, null, null, advice));
        details.stream().limit(limit).map(Finding::detail).forEach(findings::add);
        return new Evaluation(Verdict.FAIL, findings);
    }

    /** The check does not apply here (e.g. the parameter no longer exists on this version). */
    public static Evaluation notApplicable(String reason) {
        return new Evaluation(Verdict.NOT_APPLICABLE, List.of(Finding.note(reason)));
    }

    /** The check applies but could not be decided; callers must not treat this as a pass. */
    public static Evaluation unknown(String reason) {
        return new Evaluation(Verdict.UNKNOWN, List.of(Finding.note(reason)));
    }

    public Finding primary() {
        return findings.isEmpty() ? null : findings.get(0);
    }

    public List<String> details() {
        return findings.stream()
                .map(Finding::subject)
                .filter(subject -> subject != null && !subject.isBlank())
                .toList();
    }
}
