package io.opendrs.jdbc;

/**
 * Mechanism-level failure of the jdbc layer (connection, URL, metadata lookup).
 * Upper layers translate it into their own error vocabulary.
 */
public class JdbcException extends RuntimeException {

    public JdbcException(String message) {
        super(message);
    }

    public JdbcException(String message, Throwable cause) {
        super(message, cause);
    }
}
