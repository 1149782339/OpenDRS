package io.opendrs.engine.ddl.converters;

/**
 * Raised when a DDL conversion cannot be performed or produced no usable result.
 * Mechanism-level exception; upper layers translate it into their own error vocabulary.
 */
public class ConversionException extends RuntimeException {

    public ConversionException(String message) {
        super(message);
    }

    public ConversionException(String message, Throwable cause) {
        super(message, cause);
    }
}
