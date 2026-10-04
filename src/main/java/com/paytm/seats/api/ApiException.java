package com.paytm.seats.api;

import org.springframework.http.HttpStatus;

/** A domain outcome that maps to a 4xx (or deliberate 503) response with a stable machine-readable reason. */
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String reason;

    public ApiException(HttpStatus status, String reason, String message) {
        super(message, null, false, false);
        this.status = status;
        this.reason = reason;
    }

    public HttpStatus status() {
        return status;
    }

    public String reason() {
        return reason;
    }

    public static ApiException badRequest(String reason, String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, reason, message);
    }

    public static ApiException notFound(String message) {
        return new ApiException(HttpStatus.NOT_FOUND, "not_found", message);
    }

    public static ApiException unauthorized(String message) {
        return new ApiException(HttpStatus.UNAUTHORIZED, "unauthorized", message);
    }

    public static ApiException forbidden(String message) {
        return new ApiException(HttpStatus.FORBIDDEN, "forbidden", message);
    }

    public static ApiException conflict(String reason, String message) {
        return new ApiException(HttpStatus.CONFLICT, reason, message);
    }
}
