package io.opendrs.engine.precheck;

/**
 * Where the check itself got to. Kept apart from {@link Verdict} on purpose: a check that never ran
 * because the source was unreachable is {@link #BLOCKED}, not "failed" and definitely not "passed".
 */
public enum ExecutionState {
    /** Planned, not started yet - the UI paints it as "checking". */
    PENDING,
    RUNNING,
    COMPLETED,
    /** The check threw; there is no trustworthy verdict. */
    ERROR,
    /** A fact it needs could not be collected (usually the endpoint is unreachable). */
    BLOCKED
}
