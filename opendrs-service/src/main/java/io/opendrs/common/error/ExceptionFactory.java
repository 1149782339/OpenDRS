package io.opendrs.common.error;

import io.opendrs.common.i18n.ErrorMessage;

/**
 * Single entry point for throwing business exceptions. The exception carries only the business
 * {@link ErrorCode}, a message key and its arguments — translation into the request's language
 * happens once, in {@link GlobalExceptionHandler}, never at the throw site.
 */
public final class ExceptionFactory {

    public static AppException create(ErrorCode code, ErrorMessage message, Object... args) {
        return new AppException(code, message, args, null);
    }

    public static AppException create(ErrorCode code, ErrorMessage message, Object[] args, Object data) {
        return new AppException(code, message, args, data);
    }

    private ExceptionFactory() {
    }
}
