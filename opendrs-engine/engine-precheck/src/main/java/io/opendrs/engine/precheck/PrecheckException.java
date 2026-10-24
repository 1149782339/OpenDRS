package io.opendrs.engine.precheck;

/**
 * Mechanism-level precheck error: connection refused or schema/table missing. The service boundary
 * translates this into its policy exception.
 */
public class PrecheckException extends RuntimeException {

    public PrecheckException(String message) {
        super(message);
    }

    public PrecheckException(String message, Throwable cause) {
        super(message, cause);
    }
}
