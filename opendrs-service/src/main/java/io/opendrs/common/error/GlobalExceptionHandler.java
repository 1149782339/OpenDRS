package io.opendrs.common.error;

import io.opendrs.common.api.Response;
import io.opendrs.common.i18n.ErrorMessage;
import io.opendrs.common.i18n.I18nUtils;
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

/**
 * Translates exceptions into the response envelope. Business {@link AppException}s resolve their
 * {@link ErrorMessage} in the request's locale here — the single place in the system where an
 * exception becomes user-facing text.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(AppException.class)
    public ResponseEntity<Response<Object>> handleAppException(AppException ex) {
        String message = ex.getErrorMessage() != null
                ? I18nUtils.getMessage(ex.getErrorMessage(), ex.getArgs())
                : ex.getCode().name();
        return ResponseEntity
                .status(ex.getCode().getHttpStatus())
                .body(Response.fail(ex.getCode(), message, ex.getData()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Response<Void>> handleMethodArgumentNotValid(MethodArgumentNotValidException ex) {
        String details = ex.getBindingResult().getFieldErrors().stream()
                .map(GlobalExceptionHandler::formatFieldError)
                .reduce((a, b) -> a + "; " + b)
                .orElse("");
        return paramInvalid(details.isEmpty() ? null : details);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<Response<Void>> handleConstraintViolation(ConstraintViolationException ex) {
        String details = ex.getConstraintViolations().stream()
                .map(GlobalExceptionHandler::formatConstraint)
                .reduce((a, b) -> a + "; " + b)
                .orElse("");
        return paramInvalid(details.isEmpty() ? null : details);
    }

    @ExceptionHandler(HandlerMethodValidationException.class)
    public ResponseEntity<Response<Void>> handleHandlerMethodValidation(HandlerMethodValidationException ex) {
        return paramInvalid(null);
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class})
    public ResponseEntity<Response<Void>> handleUnreadable(Exception ex) {
        log.debug("Rejected request payload: {}", ex.getMessage());
        return paramInvalid(I18nUtils.getMessage(ErrorMessage.PARAM_BAD_REQUEST));
    }

    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<Response<Void>> handleDataAccess(DataAccessException ex) {
        log.error("Database error", ex);
        return ResponseEntity
                .status(ErrorCode.DB_ERROR.getHttpStatus())
                .body(Response.fail(ErrorCode.DB_ERROR, I18nUtils.getMessage(ErrorMessage.SYSTEM_DB_ERROR), null));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Response<Void>> handleUnexpected(Exception ex) {
        log.error("Unhandled error", ex);
        return ResponseEntity
                .status(ErrorCode.INTERNAL_ERROR.getHttpStatus())
                .body(Response.fail(ErrorCode.INTERNAL_ERROR,
                        I18nUtils.getMessage(ErrorMessage.SYSTEM_INTERNAL_ERROR), null));
    }

    private static ResponseEntity<Response<Void>> paramInvalid(String details) {
        String message = details == null || details.isEmpty()
                ? I18nUtils.getMessage(ErrorMessage.PARAM_INVALID)
                : I18nUtils.getMessage(ErrorMessage.PARAM_INVALID_DETAIL, details);
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
