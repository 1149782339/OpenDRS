package io.opendrs.common.error;

import org.springframework.http.HttpStatus;

public enum ErrorCode {

    SUCCESS(1000, "success", HttpStatus.OK),
    PARAM_INVALID(1001, "PARAM_INVALID", HttpStatus.OK),
    TASK_NOT_FOUND(1002, "TASK_NOT_FOUND", HttpStatus.OK),
    TASK_CONFLICT(1003, "TASK_CONFLICT", HttpStatus.OK),
    CONNECTION_NOT_FOUND(1004, "CONNECTION_NOT_FOUND", HttpStatus.OK),
    CONNECTION_TEST_FAILED(1005, "CONNECTION_TEST_FAILED", HttpStatus.OK),
    CONNECTION_IN_USE(1006, "CONNECTION_IN_USE", HttpStatus.OK),
    DB_ERROR(1500, "数据库异常", HttpStatus.INTERNAL_SERVER_ERROR),
    INTERNAL_ERROR(1501, "系统内部错误", HttpStatus.INTERNAL_SERVER_ERROR);

    private final int code;
    private final String defaultMessage;
    private final HttpStatus httpStatus;

    ErrorCode(int code, String defaultMessage, HttpStatus httpStatus) {
        this.code = code;
        this.defaultMessage = defaultMessage;
        this.httpStatus = httpStatus;
    }

    public int getCode() {
        return code;
    }

    public String getDefaultMessage() {
        return defaultMessage;
    }

    public HttpStatus getHttpStatus() {
        return httpStatus;
    }
}
