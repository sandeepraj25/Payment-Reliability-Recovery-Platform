package com.payguard.common.error;

import com.payguard.domain.payment.InvalidStateTransitionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.TypeMismatchException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;

import java.util.List;

/**
 * Turns exceptions into consistent {@link ApiError} responses.
 * Unexpected errors return a generic 500 - stack traces and SQL never leak to clients.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    ResponseEntity<ApiError> handleApi(ApiException e) {
        return ResponseEntity.status(e.status()).body(ApiError.of(e.code(), e.getMessage()));
    }

    @ExceptionHandler(InvalidStateTransitionException.class)
    ResponseEntity<ApiError> handleTransition(InvalidStateTransitionException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiError.of("INVALID_STATE_TRANSITION", e.getMessage()));
    }

    @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
    ResponseEntity<ApiError> handleOptimisticLock(ObjectOptimisticLockingFailureException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiError.of("CONCURRENT_MODIFICATION", "The resource was modified concurrently; retry"));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ApiError> handleValidation(MethodArgumentNotValidException e) {
        List<String> details = e.getBindingResult().getFieldErrors().stream()
                .map(f -> f.getField() + ": " + f.getDefaultMessage())
                .toList();
        return ResponseEntity.badRequest().body(ApiError.of("VALIDATION_FAILED", "Request is invalid", details));
    }

    @ExceptionHandler(HandlerMethodValidationException.class)
    ResponseEntity<ApiError> handleMethodValidation(HandlerMethodValidationException e) {
        return ResponseEntity.badRequest().body(ApiError.of("VALIDATION_FAILED", "Request is invalid"));
    }

    @ExceptionHandler(MissingRequestHeaderException.class)
    ResponseEntity<ApiError> handleMissingHeader(MissingRequestHeaderException e) {
        return ResponseEntity.badRequest()
                .body(ApiError.of("MISSING_HEADER", "Required header missing: " + e.getHeaderName()));
    }

    /** Also catches fractional amounts like 4999.5 (Jackson accept-float-as-int is disabled). */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ApiError> handleUnreadable(HttpMessageNotReadableException e) {
        return ResponseEntity.badRequest()
                .body(ApiError.of("MALFORMED_REQUEST", "Request body is not valid JSON for this endpoint"));
    }

    /** e.g. "abc" for a UUID or long path variable. */
    @ExceptionHandler(TypeMismatchException.class)
    ResponseEntity<ApiError> handleTypeMismatch(TypeMismatchException e) {
        return ResponseEntity.badRequest().body(ApiError.of("VALIDATION_FAILED", "Parameter has the wrong type"));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiError> handleUnexpected(Exception e) {
        // Spring's own web exceptions (404 no handler, 405 wrong method, 415 media type, ...) carry their
        // real status. Without this check the catch-all would turn a harmless 404 into a 500.
        if (e instanceof ErrorResponse er) {
            HttpStatus status = HttpStatus.resolve(er.getStatusCode().value());
            return ResponseEntity.status(er.getStatusCode())
                    .body(ApiError.of(status == null ? "HTTP_ERROR" : status.name(), "Request could not be handled"));
        }
        log.error("Unhandled error", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiError.of("INTERNAL_ERROR", "Unexpected error"));
    }
}
