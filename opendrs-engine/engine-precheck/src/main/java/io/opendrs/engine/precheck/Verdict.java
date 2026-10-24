package io.opendrs.engine.precheck;

/**
 * The conclusion of a check, independent of how severe it is and of whether the check actually
 * finished. "Not applicable" means the check does not apply to this scenario (e.g. a parameter that
 * the server version no longer has); "unknown" means it could not be decided - never conflate them.
 */
public enum Verdict {
    PASS,
    FAIL,
    NOT_APPLICABLE,
    UNKNOWN
}
