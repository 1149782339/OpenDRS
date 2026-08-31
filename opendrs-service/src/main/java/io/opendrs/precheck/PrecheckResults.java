package io.opendrs.precheck;

import java.util.ArrayList;
import java.util.List;

/** Stored JSON for async precheck: source then target CheckResult batches. */
public record PrecheckResults(List<CheckResult> source, List<CheckResult> target) {

    public PrecheckResults {
        source = source == null ? List.of() : List.copyOf(source);
        target = target == null ? List.of() : List.copyOf(target);
    }

    public static PrecheckResults empty() {
        return new PrecheckResults(List.of(), List.of());
    }

    public PrecheckResults withSource(List<CheckResult> sourceResults) {
        return new PrecheckResults(sourceResults, target);
    }

    public PrecheckResults withTarget(List<CheckResult> targetResults) {
        return new PrecheckResults(source, targetResults);
    }

    public List<CheckResult> all() {
        List<CheckResult> all = new ArrayList<>(source.size() + target.size());
        all.addAll(source);
        all.addAll(target);
        return List.copyOf(all);
    }
}
