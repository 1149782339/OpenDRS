package io.opendrs.engine.precheck;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Turns a profile plus a run context into a concrete plan.
 *
 * <p>Two things are checked here rather than at run time: every bound id resolves to a rule, and no
 * id is bound twice. A profile that selects nothing is also an error - an empty checklist would
 * otherwise be admitted and let the migration start unchecked.
 */
public final class PlanBuilder {

    private final RuleRegistry registry;

    public PlanBuilder(RuleRegistry registry) {
        this.registry = registry;
    }

    public PrecheckPlan build(PrecheckProfile profile, PrecheckContext context) {
        List<PlannedCheck> executionOrder = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (RulePack pack : profile.packs()) {
            for (RuleBinding binding : pack.select(context)) {
                if (!seen.add(binding.ruleId())) {
                    throw new IllegalStateException(
                            "Rule " + binding.ruleId() + " is bound twice in profile " + profile.id());
                }
                executionOrder.add(new PlannedCheck(registry.require(binding.ruleId()), binding.severity(), 0));
            }
        }
        if (executionOrder.isEmpty()) {
            throw new IllegalStateException("Profile " + profile.id() + " selected no checks");
        }
        Map<String, Integer> displayOrder = displayOrder(executionOrder);
        List<PlannedCheck> ordered = executionOrder.stream()
                .map(check -> new PlannedCheck(check.rule(), check.severity(), displayOrder.get(check.ruleId())))
                .toList();
        return new PrecheckPlan(profile.id(), ordered);
    }

    /** Group order first, pack order inside a group (the sort is stable). */
    private static Map<String, Integer> displayOrder(List<PlannedCheck> checks) {
        List<PlannedCheck> sorted = new ArrayList<>(checks);
        sorted.sort(Comparator.comparingInt(check -> check.rule().meta().group().order()));
        Map<String, Integer> order = new HashMap<>();
        for (int i = 0; i < sorted.size(); i++) {
            order.put(sorted.get(i).ruleId(), i);
        }
        return order;
    }
}
