package io.opendrs.engine.replay.exception;

/**
 * Mechanism-level mapping error: conflicting or inconsistent schema/table mappings. The service
 * boundary translates this into its policy exception.
 */
public class MappingException extends RuntimeException {

    public MappingException(String message) {
        super(message);
    }

    public MappingException(String message, Throwable cause) {
        super(message, cause);
    }
}
