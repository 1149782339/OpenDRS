package io.opendrs.engine.precheck;

/**
 * Thrown when a check needs a fact that could not be collected - typically because that endpoint
 * could not be reached. The runner turns it into {@link ExecutionState#BLOCKED} with the root cause,
 * so the UI can tell "we could not look" apart from "we looked and it is wrong".
 */
public class DependencyUnavailableException extends RuntimeException {

    private final String rootCause;

    public DependencyUnavailableException(String rootCause, String message) {
        super(message);
        this.rootCause = rootCause;
    }

    /** Id of the check that already reported the failure, e.g. {@code source.connection}. */
    public String rootCause() {
        return rootCause;
    }
}
