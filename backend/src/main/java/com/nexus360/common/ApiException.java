package com.nexus360.common;

import java.util.List;
import lombok.Getter;
import org.springframework.http.HttpStatus;

/** Business error with an HTTP status and a message that is safe to show to the client. */
@Getter
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    /** Optional per-field details, rendered as {@code ApiError.fieldErrors} (null for most errors). */
    private final List<ApiError.FieldError> fieldErrors;

    public ApiException(HttpStatus status, String message) {
        this(status, message, null);
    }

    public ApiException(HttpStatus status, String message, List<ApiError.FieldError> fieldErrors) {
        super(message);
        this.status = status;
        this.fieldErrors = fieldErrors;
    }

    public static ApiException notFound(String entity, Object id) {
        return new ApiException(HttpStatus.NOT_FOUND, entity + " " + id + " was not found");
    }

    public static ApiException badRequest(String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, message);
    }

    /** {@code 400} for one request field, e.g. a password that fails the policy: {@code fieldErrors: [{field, message}]}. */
    public static ApiException invalidField(String field, String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, message, List.of(new ApiError.FieldError(field, message)));
    }

    public static ApiException conflict(String message) {
        return new ApiException(HttpStatus.CONFLICT, message);
    }

    public static ApiException forbidden(String message) {
        return new ApiException(HttpStatus.FORBIDDEN, message);
    }
}
