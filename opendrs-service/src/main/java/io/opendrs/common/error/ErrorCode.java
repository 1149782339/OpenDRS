package io.opendrs.common.error;

import org.springframework.http.HttpStatus;

/**
 * Stable business codes for the frontend's logic branches (1000–1999). User-facing text lives in
 * the message bundles keyed by {@link io.opendrs.common.i18n.ErrorMessage}, never here — a code
 * must never change because its wording did.
 */
public enum ErrorCode {

    SUCCESS(1000, HttpStatus.OK),
    PARAM_INVALID(1001, HttpStatus.OK),
    TASK_NOT_FOUND(1002, HttpStatus.OK),
    TASK_CONFLICT(1003, HttpStatus.OK),
    CONNECTION_NOT_FOUND(1004, HttpStatus.OK),
    CONNECTION_TEST_FAILED(1005, HttpStatus.OK),
    CONNECTION_IN_USE(1006, HttpStatus.OK),
    METADATA_READ_FAILED(1007, HttpStatus.OK),
    DB_ERROR(1500, HttpStatus.INTERNAL_SERVER_ERROR),
    INTERNAL_ERROR(1501, HttpStatus.INTERNAL_SERVER_ERROR);

    private final int code;
    private final HttpStatus httpStatus;

    ErrorCode(int code, HttpStatus httpStatus) {
        this.code = code;
        this.httpStatus = httpStatus;
    }

    public int getCode() {
        return code;
    }

    public HttpStatus getHttpStatus() {
        return httpStatus;
    }
}
