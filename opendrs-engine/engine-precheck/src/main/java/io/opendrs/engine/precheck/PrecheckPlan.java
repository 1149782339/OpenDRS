package io.opendrs.engine.precheck;

import java.util.List;

/**
 * The checklist for one run, in <em>execution</em> order. Display order lives on each
 * {@link PlannedCheck#order()} because it follows the UI grouping, not the dependency order: the
 * connection checks run first but are shown last, under 网络状态.
 */
public record PrecheckPlan(String profileId, List<PlannedCheck> checks) {

    public PrecheckPlan {
        checks = List.copyOf(checks);
    }

    /**
     * Every row as it should appear the moment the run starts - all of them, all pending, already
     * in display order so the stored JSON reads the same way the wizard renders it.
     */
    public List<PrecheckItem> pendingItems() {
        return checks.stream()
                .map(check -> PrecheckItem.pending(check.rule().meta(), check.severity(), check.order()))
                .sorted(java.util.Comparator.comparingInt(PrecheckItem::order))
                .toList();
    }
}
