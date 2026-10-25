package io.opendrs.common.error;

import io.opendrs.common.i18n.ErrorMessage;

/**
 * Business exception. Carries a stable {@link ErrorCode} for the frontend's logic branches plus an
 * {@link ErrorMessage} key and arguments; the human-readable text is resolved from the properties
 * bundle at the API boundary ({@link GlobalExceptionHandler}) in the caller's language.
 *
 * <p>{@link #getMessage()} intentionally returns the message key — it is for logs and debugging,
 * not for end users.
 */
public class AppException extends RuntimeException {

    private final ErrorCode code;
    private final ErrorMessage errorMessage;
    private final Object[] args;
    private final Object data;

    AppException(ErrorCode code, ErrorMessage errorMessage, Object[] args, Object data) {
        super(errorMessage != null ? errorMessage.getKey() : code.name());
        this.code = code;
        this.errorMessage = errorMessage;
        this.args = args != null ? args.clone() : new Object[0];
        this.data = data;
    }

    public ErrorCode getCode() {
        return code;
    }

    public ErrorMessage getErrorMessage() {
        return errorMessage;
    }

    public Object[] getArgs() {
        return args.clone();
    }

    public Object getData() {
        return data;
    }
}
