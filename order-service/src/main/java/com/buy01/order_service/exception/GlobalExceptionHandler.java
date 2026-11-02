package com.buy01.order_service.exception;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.context.MessageSourceResolvable;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.validation.method.ParameterErrors;
import org.springframework.validation.method.ParameterValidationResult;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import com.buy01.order_service.dtos.response.ErrorResponse;

import jakarta.servlet.http.HttpServletRequest;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final String BAD_REQUEST = "Bad Request";
    private static final String VALIDATION_FAILED = "Validation Failed";
    private static final String INVALID_INPUT = "Invalid input data";
    private static final String DEFAULT_FIELD_MESSAGE = "invalid value";

    /** Invalid @RequestBody or @ModelAttribute (body fields, filter query parameters). */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidationErrors(MethodArgumentNotValidException ex,
            HttpServletRequest request) {
        Map<String, String> errors = new LinkedHashMap<>();
        for (FieldError fieldError : ex.getBindingResult().getFieldErrors()) {
            errors.putIfAbsent(fieldError.getField(), messageOf(fieldError));
        }
        ex.getBindingResult().getGlobalErrors()
                .forEach(error -> errors.putIfAbsent(error.getObjectName(), messageOf(error)));
        return build(HttpStatus.BAD_REQUEST, VALIDATION_FAILED, INVALID_INPUT, request, errors);
    }

    /**
     * Raised instead of MethodArgumentNotValidException when the handler also has constraints
     * directly on parameters (e.g. the Idempotency-Key header). Body errors keep their field names.
     */
    @ExceptionHandler(HandlerMethodValidationException.class)
    public ResponseEntity<ErrorResponse> handleMethodValidation(HandlerMethodValidationException ex,
            HttpServletRequest request) {
        Map<String, String> errors = new LinkedHashMap<>();
        for (ParameterValidationResult result : ex.getParameterValidationResults()) {
            if (result instanceof ParameterErrors parameterErrors) {
                parameterErrors.getFieldErrors()
                        .forEach(error -> errors.putIfAbsent(error.getField(), messageOf(error)));
                parameterErrors.getGlobalErrors()
                        .forEach(error -> errors.putIfAbsent(error.getObjectName(), messageOf(error)));
            } else {
                String name = result.getMethodParameter().getParameterName();
                result.getResolvableErrors()
                        .forEach(error -> errors.putIfAbsent(name == null ? "parameter" : name, messageOf(error)));
            }
        }
        return build(HttpStatus.BAD_REQUEST, VALIDATION_FAILED, INVALID_INPUT, request, errors);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleUnreadableBody(HttpMessageNotReadableException ex,
            HttpServletRequest request) {
        return build(HttpStatus.BAD_REQUEST, BAD_REQUEST, "Malformed request body", request, null);
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ErrorResponse> handleTypeMismatch(MethodArgumentTypeMismatchException ex,
            HttpServletRequest request) {
        return build(HttpStatus.BAD_REQUEST, BAD_REQUEST, "Invalid value for parameter " + ex.getName(),
                request, null);
    }

    @ExceptionHandler(BadRequestException.class)
    public ResponseEntity<ErrorResponse> handleBadRequest(BadRequestException ex, HttpServletRequest request) {
        return build(HttpStatus.BAD_REQUEST, BAD_REQUEST, ex.getMessage(), request, null);
    }

    @ExceptionHandler(UnauthorizedException.class)
    public ResponseEntity<ErrorResponse> handleUnauthorized(UnauthorizedException ex, HttpServletRequest request) {
        return build(HttpStatus.UNAUTHORIZED, "Unauthorized", ex.getMessage(), request, null);
    }

    @ExceptionHandler(ForbiddenException.class)
    public ResponseEntity<ErrorResponse> handleForbidden(ForbiddenException ex, HttpServletRequest request) {
        return build(HttpStatus.FORBIDDEN, "Forbidden", ex.getMessage(), request, null);
    }

    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleNotFound(ResourceNotFoundException ex, HttpServletRequest request) {
        return build(HttpStatus.NOT_FOUND, "Not Found", ex.getMessage(), request, null);
    }

    @ExceptionHandler({ConflictException.class, org.springframework.dao.DuplicateKeyException.class})
    public ResponseEntity<ErrorResponse> handleConflict(RuntimeException ex, HttpServletRequest request) {
        return build(HttpStatus.CONFLICT, "Conflict", ex instanceof ConflictException ? ex.getMessage() : "Cart changed concurrently. Reload and retry", request, null);
    }

    @ExceptionHandler(OptimisticLockingFailureException.class)
    public ResponseEntity<ErrorResponse> handleConcurrentUpdate(OptimisticLockingFailureException ex,
            HttpServletRequest request) {
        return build(HttpStatus.CONFLICT, "Conflict", "The order was modified at the same time, please retry",
                request, null);
    }

    @ExceptionHandler(UpstreamServiceException.class)
    public ResponseEntity<ErrorResponse> handleUpstream(UpstreamServiceException ex, HttpServletRequest request) {
        return build(HttpStatus.BAD_GATEWAY, "Bad Gateway", ex.getMessage(), request, null);
    }

    /**
     * Spring MVC exceptions (missing header, unsupported method, unknown path...)
     * carry their own status; anything else is an unexpected 500 whose details stay in the logs.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpected(Exception ex, HttpServletRequest request) {
        if (ex instanceof org.springframework.web.ErrorResponse springError) {
            HttpStatusCode status = springError.getStatusCode();
            String detail = springError.getBody().getDetail();
            return build(status, reasonOf(status), detail == null ? "Request failed" : detail, request, null);
        }
        return build(HttpStatus.INTERNAL_SERVER_ERROR, "Internal Server Error",
                "An unexpected error occurred", request, null);
    }

    private ResponseEntity<ErrorResponse> build(HttpStatusCode status, String error, String message,
            HttpServletRequest request, Map<String, String> validationErrors) {
        ErrorResponse body = new ErrorResponse(LocalDateTime.now(ZoneOffset.UTC), status.value(), error, message,
                request.getRequestURI(), validationErrors);
        return ResponseEntity.status(status).body(body);
    }

    private static String messageOf(MessageSourceResolvable error) {
        String message = error.getDefaultMessage();
        return message == null ? DEFAULT_FIELD_MESSAGE : message;
    }

    private static String reasonOf(HttpStatusCode status) {
        HttpStatus resolved = HttpStatus.resolve(status.value());
        return resolved == null ? "Error" : resolved.getReasonPhrase();
    }
}
