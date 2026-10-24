package io.opendrs.engine.replay.exception;

/**
 * Applier Exception
 *
 */
public class ApplierException extends Exception {
    public ApplierException(String message, Throwable cause) {
        super(message, cause);
    }

    public ApplierException(String message) {
        super(message);
    }

    public ApplierException(Throwable cause) {
        super(cause);
    }
}
