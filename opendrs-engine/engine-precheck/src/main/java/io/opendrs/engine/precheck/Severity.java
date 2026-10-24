package io.opendrs.engine.precheck;

/**
 * How much a failed verdict matters. A {@link #WARNING} that fails is still a failure - the wizard
 * shows it as "please confirm" and the user may proceed, but it is never rewritten into a pass.
 */
public enum Severity {
    BLOCKER,
    WARNING,
    INFO
}
