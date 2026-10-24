package io.opendrs.engine.precheck;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Id to rule. The registry is the only place that knows every implementation; a profile only names
 * ids, so a check can be added, removed or re-severitied without touching the runner or the UI.
 *
 * <p>Both failure modes are configuration errors and fail at assembly time: a duplicate id would
 * make the plan ambiguous, an unknown id would silently drop a check.
 */
public final class RuleRegistry {

    private final Map<String, CheckRule> byId;

    public RuleRegistry(List<CheckRule> rules) {
        Map<String, CheckRule> map = new LinkedHashMap<>();
        for (CheckRule rule : rules) {
            String id = rule.meta().id();
            CheckRule previous = map.putIfAbsent(id, rule);
            if (previous != null) {
                throw new IllegalStateException("Duplicate precheck rule id: " + id);
            }
        }
        this.byId = Map.copyOf(map);
    }

    public CheckRule require(String id) {
        CheckRule rule = byId.get(id);
        if (rule == null) {
            throw new IllegalStateException("Unknown precheck rule: " + id);
        }
        return rule;
    }

    public Set<String> ids() {
        return byId.keySet();
    }
}
