package com.nexus360.common;

import com.nexus360.audit.AuditService;
import com.nexus360.security.Actor;
import com.nexus360.security.CurrentActor;
import jakarta.persistence.OptimisticLockException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authorization.AuthorizationDeniedException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/** Translates exceptions into {@link ApiError} responses without leaking internals. */
@Slf4j
@RestControllerAdvice
@RequiredArgsConstructor
public class GlobalExceptionHandler {

    private final AuditService audit;

    @ExceptionHandler(ApiException.class)
    ResponseEntity<ApiError> handleApi(ApiException ex, HttpServletRequest request) {
        return build(ex.getStatus(), ex.getMessage(), request, ex.getFieldErrors());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ApiError> handleValidation(MethodArgumentNotValidException ex, HttpServletRequest request) {
        List<ApiError.FieldError> fields = ex.getBindingResult().getFieldErrors().stream()
                .map(f -> new ApiError.FieldError(f.getField(), f.getDefaultMessage()))
                .toList();
        return build(HttpStatus.BAD_REQUEST, "Validation failed", request, fields);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    ResponseEntity<ApiError> handleConstraint(ConstraintViolationException ex, HttpServletRequest request) {
        List<ApiError.FieldError> fields = ex.getConstraintViolations().stream()
                .map(v -> new ApiError.FieldError(v.getPropertyPath().toString(), v.getMessage()))
                .toList();
        return build(HttpStatus.BAD_REQUEST, "Validation failed", request, fields);
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class,
            MissingServletRequestParameterException.class, IllegalArgumentException.class})
    ResponseEntity<ApiError> handleBadInput(Exception ex, HttpServletRequest request) {
        String message = ex instanceof MissingServletRequestParameterException m
                ? "Missing request parameter '" + m.getParameterName() + "'"
                : ex instanceof MethodArgumentTypeMismatchException t ? "Invalid value for '" + t.getName() + "'"
                : ex instanceof IllegalArgumentException && ex.getMessage() != null && !ex.getMessage().startsWith("No enum constant")
                ? ex.getMessage()
                : "Malformed request";
        return build(HttpStatus.BAD_REQUEST, message, request, null);
    }

    @ExceptionHandler({AccessDeniedException.class, AuthorizationDeniedException.class})
    ResponseEntity<ApiError> handleDenied(Exception ex, HttpServletRequest request) {
        // Method-security denials surface here rather than in the security filter chain, so they are audited here.
        Actor actor = CurrentActor.orSystem();
        audit.recordAs(actor.id(), actor.email(), "ACCESS_DENIED", "HTTP", null,
                Map.of("method", request.getMethod(), "path", request.getRequestURI()), AuditService.DENIED);
        return build(HttpStatus.FORBIDDEN, "You do not have permission to perform this action", request, null);
    }

    @ExceptionHandler({ObjectOptimisticLockingFailureException.class, OptimisticLockException.class})
    ResponseEntity<ApiError> handleOptimisticLock(Exception ex, HttpServletRequest request) {
        return build(HttpStatus.CONFLICT, "The record was changed by someone else. Reload and try again.", request, null);
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<ApiError> handleIntegrity(DataIntegrityViolationException ex, HttpServletRequest request) {
        log.warn("Data integrity violation on {}: {}", request.getRequestURI(), ex.getMostSpecificCause().getMessage());
        return build(HttpStatus.CONFLICT, "The request conflicts with existing data (duplicate or invalid reference)", request, null);
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    ResponseEntity<ApiError> handleUploadSize(MaxUploadSizeExceededException ex, HttpServletRequest request) {
        return build(HttpStatus.PAYLOAD_TOO_LARGE, "File exceeds the 5 MB upload limit", request, null);
    }

    @ExceptionHandler(MultipartException.class)
    ResponseEntity<ApiError> handleMultipart(MultipartException ex, HttpServletRequest request) {
        return build(HttpStatus.BAD_REQUEST, "Invalid multipart upload", request, null);
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    ResponseEntity<ApiError> handleMethod(HttpRequestMethodNotSupportedException ex, HttpServletRequest request) {
        return build(HttpStatus.METHOD_NOT_ALLOWED, "Method not allowed", request, null);
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    ResponseEntity<ApiError> handleMediaType(HttpMediaTypeNotSupportedException ex, HttpServletRequest request) {
        return build(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Unsupported content type", request, null);
    }

    @ExceptionHandler(NoResourceFoundException.class)
    ResponseEntity<ApiError> handleNoResource(NoResourceFoundException ex, HttpServletRequest request) {
        return build(HttpStatus.NOT_FOUND, "Not found", request, null);
    }

    /**
     * The client went away (closed tab, dropped live-notification stream). Nothing can be written back, and an
     * ApiError body would fail on a text/event-stream response, so the request simply ends.
     */
    @ExceptionHandler(AsyncRequestNotUsableException.class)
    void handleClientGone(AsyncRequestNotUsableException ex, HttpServletRequest request) {
        log.debug("Client disconnected from {} {}: {}", request.getMethod(), request.getRequestURI(), ex.getMessage());
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiError> handleUnexpected(Exception ex, HttpServletRequest request) {
        if (isClientAbort(ex)) {
            log.debug("Client disconnected from {} {}: {}", request.getMethod(), request.getRequestURI(), ex.getMessage());
            return null;
        }
        log.error("Unhandled error on {} {}", request.getMethod(), request.getRequestURI(), ex);
        return build(HttpStatus.INTERNAL_SERVER_ERROR, "An unexpected error occurred", request, null);
    }

    /** A write to a connection the client already closed ("broken pipe", "connection aborted/reset"). */
    static boolean isClientAbort(Throwable ex) {
        for (Throwable t = ex; t != null; t = t.getCause()) {
            if (t instanceof AsyncRequestNotUsableException || t.getClass().getSimpleName().equals("ClientAbortException")) {
                return true;
            }
            if (t instanceof java.io.IOException && t.getMessage() != null) {
                String message = t.getMessage().toLowerCase(java.util.Locale.ROOT);
                if (message.contains("broken pipe") || message.contains("connection was aborted") || message.contains("connection reset")) {
                    return true;
                }
            }
        }
        return false;
    }

    public static ApiError error(HttpStatus status, String message, String path) {
        return new ApiError(status.value(), status.getReasonPhrase(), message, path, Instant.now(), null);
    }

    private ResponseEntity<ApiError> build(HttpStatus status, String message, HttpServletRequest request,
                                           List<ApiError.FieldError> fields) {
        return ResponseEntity.status(status).body(new ApiError(status.value(), status.getReasonPhrase(), message,
                request.getRequestURI(), Instant.now(), fields == null || fields.isEmpty() ? null : fields));
    }
}
