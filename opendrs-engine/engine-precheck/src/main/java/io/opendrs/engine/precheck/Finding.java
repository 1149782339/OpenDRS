package io.opendrs.engine.precheck;

/**
 * One piece of evidence behind a verdict, in the three parts the wizard's detail dialog shows:
 * why it failed, what was actually seen versus what was expected, and what the user can do.
 *
 * <p>A finding that only carries {@code subject} is a detail line (a table name, a parameter), used
 * by checks that cover many objects.
 */
public record Finding(String subject, String reason, String actual, String expected, String advice) {

    public static Finding of(String reason, String actual, String expected, String advice) {
        return new Finding(null, reason, actual, expected, advice);
    }

    /** A detail line such as one table that has no primary key. */
    public static Finding detail(String subject) {
        return new Finding(subject, null, null, null, null);
    }

    /** A free-form note used by "not applicable" and "unknown" verdicts. */
    public static Finding note(String reason) {
        return new Finding(null, reason, null, null, null);
    }
}
