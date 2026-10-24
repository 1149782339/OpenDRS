package io.opendrs.engine.precheck;

/**
 * Selects one rule and states how much a failure matters for this scenario. Severity lives here and
 * not in the rule: the same check can be a blocker for one migration strategy and a warning for
 * another.
 */
public record RuleBinding(String ruleId, Severity severity) {
}
