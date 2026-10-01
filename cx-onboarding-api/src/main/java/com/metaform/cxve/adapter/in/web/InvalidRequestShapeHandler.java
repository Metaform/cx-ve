package com.metaform.cxve.adapter.in.web;

import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Logs every 400 that is triggered by an invalid request shape and returns the exact error
 * message to the caller (in the {@code detail} field of an RFC 9457 problem response). Covers
 * bean-validation failures on {@code @Valid} request bodies (missing/blank/empty required
 * fields, see e.g. {@link com.metaform.cxve.domain.model.PartnerRegistrationData}) as well as
 * unreadable payloads (malformed JSON, wrong types, unknown enum values).
 */
@RestControllerAdvice
public class InvalidRequestShapeHandler {

    private static final Logger log = LoggerFactory.getLogger(InvalidRequestShapeHandler.class);

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail onValidationFailure(MethodArgumentNotValidException exception) {
        var message = exception.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .sorted()
                .collect(Collectors.joining("; "));
        return badRequest(message);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ProblemDetail onUnreadableBody(HttpMessageNotReadableException exception) {
        return badRequest(exception.getMessage());
    }

    private ProblemDetail badRequest(String message) {
        log.warn("Rejected request with 400 due to invalid shape: {}", message);
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, message);
    }
}
