package io.opendrs.common.error;

import io.opendrs.common.api.Response;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(AppException.class)
    public ResponseEntity<Response<Object>> handleAppException(AppException ex) {
        return ResponseEntity
                .status(ex.getCode().getHttpStatus())
                .body(Response.fail(ex.getCode(), ex.getMessage(), ex.getData()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Response<Void>> handleMethodArgumentNotValid(MethodArgumentNotValidException ex) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .map(GlobalExceptionHandler::formatFieldError)
                .reduce((a, b) -> a + "; " + b)
                .orElse(ErrorCode.PARAM_INVALID.getDefaultMessage());
        return paramInvalid(message);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<Response<Void>> handleConstraintViolation(ConstraintViolationException ex) {
        String message = ex.getConstraintViolations().stream()
                .map(GlobalExceptionHandler::formatConstraint)
                .reduce((a, b) -> a + "; " + b)
                .orElse(ErrorCode.PARAM_INVALID.getDefaultMessage());
        return paramInvalid(message);
    }

    @ExceptionHandler(HandlerMethodValidationException.class)
    public ResponseEntity<Response<Void>> handleHandlerMethodValidation(HandlerMethodValidationException ex) {
        return paramInvalid(ErrorCode.PARAM_INVALID.getDefaultMessage());
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class})
    public ResponseEntity<Response<Void>> handleUnreadable(Exception ex) {
        log.debug("Rejected request payload: {}", ex.getMessage());
        return paramInvalid("Request body or parameter is invalid");
    }

    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<Response<Void>> handleDataAccess(DataAccessException ex) {
        log.error("Database error", ex);
        return ResponseEntity
                .status(ErrorCode.DB_ERROR.getHttpStatus())
                .body(Response.fail(ErrorCode.DB_ERROR, "数据库异常", null));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Response<Void>> handleUnexpected(Exception ex) {
        log.error("Unhandled error", ex);
        return ResponseEntity
                .status(ErrorCode.INTERNAL_ERROR.getHttpStatus())
                .body(Response.fail(ErrorCode.INTERNAL_ERROR, "系统内部错误", null));
    }

    private static ResponseEntity<Response<Void>> paramInvalid(String message) {
        return ResponseEntity
                .status(ErrorCode.PARAM_INVALID.getHttpStatus())
                .body(Response.fail(ErrorCode.PARAM_INVALID, message, null));
    }

    private static String formatFieldError(FieldError error) {
        return error.getField() + " " + error.getDefaultMessage();
    }

    private static String formatConstraint(ConstraintViolation<?> violation) {
        return violation.getPropertyPath() + " " + violation.getMessage();
    }
}
