package io.opendrs.common.error;

public class AppException extends RuntimeException {

    private final ErrorCode code;
    private final Object data;

    private AppException(ErrorCode code, String message, Object data) {
        super(message);
        this.code = code;
        this.data = data;
    }

    public static AppException of(ErrorCode code) {
        return new AppException(code, code.getDefaultMessage(), null);
    }

    public static AppException of(ErrorCode code, String message) {
        return new AppException(code, message, null);
    }

    public static AppException of(ErrorCode code, String message, Object data) {
        return new AppException(code, message, data);
    }

    public ErrorCode getCode() {
        return code;
    }

    public Object getData() {
        return data;
    }
}
