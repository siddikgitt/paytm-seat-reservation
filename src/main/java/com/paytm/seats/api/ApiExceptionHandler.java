package com.paytm.seats.api;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.ServletRequestBindingException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import com.paytm.seats.observability.RequestContext;

@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ErrorResponse> handleApi(ApiException e) {
        ResponseEntity<ErrorResponse> response = respond(e.status(), e.reason(), e.getMessage());
        if (e.status() == HttpStatus.TOO_MANY_REQUESTS) {
            return ResponseEntity.status(response.getStatusCode()).header(HttpHeaders.RETRY_AFTER, "2")
                    .body(response.getBody());
        }
        return response;
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleUnreadable(HttpMessageNotReadableException e) {
        return respond(HttpStatus.BAD_REQUEST, "invalid_request", "malformed JSON body");
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ErrorResponse> handleTypeMismatch(MethodArgumentTypeMismatchException e) {
        return respond(HttpStatus.NOT_FOUND, "not_found", "no such " + e.getName());
    }

    @ExceptionHandler(ServletRequestBindingException.class)
    public ResponseEntity<ErrorResponse> handleBinding(ServletRequestBindingException e) {
        return respond(HttpStatus.BAD_REQUEST, "invalid_request", e.getMessage());
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ErrorResponse> handleNoResource(NoResourceFoundException e) {
        return respond(HttpStatus.NOT_FOUND, "not_found", "no such route");
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ErrorResponse> handleMethod(HttpRequestMethodNotSupportedException e) {
        return respond(HttpStatus.METHOD_NOT_ALLOWED, "method_not_allowed", e.getMessage());
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ErrorResponse> handleMediaType(HttpMediaTypeNotSupportedException e) {
        return respond(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "unsupported_media_type", "use application/json");
    }

    /** Pool exhaustion or a lock retry budget running out: tell the client to retry, never pretend it was a decline. */
    @ExceptionHandler({CannotGetJdbcConnectionException.class, CannotCreateTransactionException.class,
            TransientDataAccessException.class})
    public ResponseEntity<ErrorResponse> handleTransient(Exception e) {
        log.warn("transient datastore failure", e);
        RequestContext.outcome("error", "unavailable");
        ResponseEntity<ErrorResponse> response = respond(HttpStatus.SERVICE_UNAVAILABLE, "unavailable",
                "temporarily unavailable, retry with the same idempotency key");
        return ResponseEntity.status(response.getStatusCode())
                .header(HttpHeaders.RETRY_AFTER, "1")
                .body(response.getBody());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpected(Exception e) {
        log.error("unhandled exception", e);
        RequestContext.outcome("error", "internal");
        return respond(HttpStatus.INTERNAL_SERVER_ERROR, "internal", "internal error");
    }

    private static ResponseEntity<ErrorResponse> respond(HttpStatus status, String reason, String message) {
        return ResponseEntity.status(status)
                .body(new ErrorResponse(status.getReasonPhrase(), reason, message, MDC.get(RequestContext.REQUEST_ID)));
    }
}
