package io.opendrs.engine.precheck;

/**
 * A rule that survived assembly: it resolved to an implementation, carries the severity the profile
 * gave it, and knows where it sits on screen ({@code order}) independently of when it runs.
 */
public record PlannedCheck(CheckRule rule, Severity severity, int order) {

    public String ruleId() {
        return rule.meta().id();
    }
}
