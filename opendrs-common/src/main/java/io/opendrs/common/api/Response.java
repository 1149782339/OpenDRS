package io.opendrs.common.api;

import io.opendrs.common.error.ErrorCode;

public class Response<T> {

    private int code;
    private String message;
    private T data;

    public Response() {
    }

    public Response(int code, String message, T data) {
        this.code = code;
        this.message = message;
        this.data = data;
    }

    public static <T> Response<T> success(T data) {
        return new Response<>(ErrorCode.SUCCESS.getCode(), "success", data);
    }

    public static Response<Void> success() {
        return new Response<>(ErrorCode.SUCCESS.getCode(), "success", null);
    }

    public static <T> Response<T> fail(ErrorCode code, String message, T data) {
        return new Response<>(code.getCode(), message, data);
    }

    public int getCode() {
        return code;
    }

    public void setCode(int code) {
        this.code = code;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public T getData() {
        return data;
    }

    public void setData(T data) {
        this.data = data;
    }
}
