package com.eternax.recon.platform.web;

import com.eternax.recon.exception.ErrorCode;
import com.eternax.recon.exception.EternaSyncException;
import com.eternax.recon.platform.context.RequestContext;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/** Single place where exceptions become responses (LLD 13.4, table 5.4). */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger LOG = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(EternaSyncException.class)
    public ResponseEntity<ErrorResponse> handleDomain(EternaSyncException e) {
        HttpStatus status =
                switch (e.category()) {
                    case VALIDATION -> HttpStatus.BAD_REQUEST;
                    case NOT_FOUND -> HttpStatus.NOT_FOUND;
                    case CONFLICT -> HttpStatus.CONFLICT;
                    case FORBIDDEN -> HttpStatus.FORBIDDEN;
                    case UNAVAILABLE -> HttpStatus.SERVICE_UNAVAILABLE;
                    case INTERNAL -> HttpStatus.INTERNAL_SERVER_ERROR;
                };
        if (status.is5xxServerError()) {
            LOG.error("domain_failure errorCode={} message={}", e.errorCode(), e.getMessage(), e);
            return body(status, e.errorCode().name(), "An internal error occurred.", List.of());
        }
        LOG.warn("request_rejected errorCode={} message={}", e.errorCode(), e.getMessage());
        return body(status, e.errorCode().name(), e.getMessage(), List.of());
    }

    @ExceptionHandler(OptimisticLockingFailureException.class)
    public ResponseEntity<ErrorResponse> handleLock(OptimisticLockingFailureException e) {
        return body(
                HttpStatus.CONFLICT,
                ErrorCode.PER_7001_OPTIMISTIC_LOCK_CONFLICT.name(),
                "The resource was modified concurrently. Re-fetch and retry.",
                List.of());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException e) {
        List<String> details =
                e.getBindingResult().getFieldErrors().stream()
                        .map(f -> f.getField() + ": " + f.getDefaultMessage())
                        .toList();
        return body(
                HttpStatus.BAD_REQUEST,
                ErrorCode.ING_2001_SCHEMA_VALIDATION_FAILED.name(),
                "Request validation failed.",
                details);
    }

    @ExceptionHandler({
        HttpMessageNotReadableException.class,
        MethodArgumentTypeMismatchException.class,
        MissingRequestHeaderException.class,
        IllegalArgumentException.class
    })
    public ResponseEntity<ErrorResponse> handleBadRequest(Exception e) {
        LOG.warn("bad_request message={}", e.getMessage());
        return body(
                HttpStatus.BAD_REQUEST,
                ErrorCode.ING_2001_SCHEMA_VALIDATION_FAILED.name(),
                "Malformed request: " + rootMessage(e),
                List.of());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpected(Exception e) {
        LOG.error("unexpected_exception", e);
        return body(
                HttpStatus.INTERNAL_SERVER_ERROR,
                ErrorCode.INF_8000_UNEXPECTED.name(),
                "An internal error occurred.",
                List.of());
    }

    private static String rootMessage(Throwable t) {
        return t.getMessage() == null
                ? t.getClass().getSimpleName()
                : t.getMessage().split("\n")[0];
    }

    private ResponseEntity<ErrorResponse> body(
            HttpStatus status, String code, String message, List<String> details) {
        String correlationId = MDC.get("correlationId");
        if (correlationId == null) {
            correlationId = UUID.randomUUID().toString();
        }
        return ResponseEntity.status(status)
                .header(RequestContext.CORRELATION_HEADER, correlationId)
                .body(new ErrorResponse(code, message, Instant.now(), correlationId, details));
    }
}
