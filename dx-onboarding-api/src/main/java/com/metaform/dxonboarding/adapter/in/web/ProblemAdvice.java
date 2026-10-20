package com.metaform.dxonboarding.adapter.in.web;

import com.metaform.dxonboarding.domain.FieldError;
import com.metaform.dxonboarding.domain.ProblemCode;
import com.metaform.dxonboarding.domain.ProblemException;
import com.metaform.dxonboarding.domain.model.onboarding.UseCase;
import java.net.URI;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.exc.MismatchedInputException;

/**
 * Answers every refusal as the TSP does: an RFC 9457 problem whose {@code type} is
 * {@code urn:decade-x:tsp:problem:<code>}, with the field problems in {@code errors} and, for a
 * document slot already taken, the document on file in {@code documentId}. The framework's own
 * refusals keep their status; a body that cannot be read is an {@code invalid-request}, an
 * oversized upload {@code content-too-large}, and anything unexpected an {@code internal-error}.
 */
@RestControllerAdvice
public class ProblemAdvice extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ProblemAdvice.class);

    @ExceptionHandler(ProblemException.class)
    public ResponseEntity<Object> problem(ProblemException e, WebRequest request) {
        return problem(e.code(), e.detail(), e.errors(), e.documentId(), request);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Object> unexpected(Exception e, WebRequest request) {
        log.error("onboarding request call failed unexpectedly", e);
        return problem(ProblemCode.INTERNAL_ERROR, "A failure on the TSP's side", List.of(), null, request);
    }

    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(HttpMessageNotReadableException e,
                                                                  HttpHeaders headers, HttpStatusCode status,
                                                                  WebRequest request) {
        var error = e.getCause() instanceof JacksonException jackson
                ? fieldError(jackson)
                : new FieldError("body", "required, as JSON");
        return problem(ProblemCode.INVALID_REQUEST, "The body is not a valid request: %s: %s"
                .formatted(error.field(), error.message()), List.of(error), null, request);
    }

    // reaches this handler only because multipart requests are resolved lazily (application.yaml):
    // eagerly, the limit is hit before a handler is chosen
    @Override
    protected ResponseEntity<Object> handleMaxUploadSizeExceededException(MaxUploadSizeExceededException e,
                                                                          HttpHeaders headers, HttpStatusCode status,
                                                                          WebRequest request) {
        return problem(ProblemCode.CONTENT_TOO_LARGE, "file: larger than 10 MB", List.of(), null, request);
    }

    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception e, Object body, HttpHeaders headers,
                                                             HttpStatusCode statusCode, WebRequest request) {
        // the framework's refusals in this API's terms, where it has one for them
        if (body instanceof ProblemDetail problem && URI.create("about:blank").equals(problem.getType())) {
            var code = switch (statusCode.value()) {
                case 400 -> ProblemCode.INVALID_REQUEST;
                case 404 -> ProblemCode.NOT_FOUND;
                default -> null;
            };
            if (code != null) {
                problem.setType(URI.create(code.type()));
                problem.setTitle(code.title());
            }
        }
        return super.handleExceptionInternal(e, body, headers, statusCode, request);
    }

    private static ResponseEntity<Object> problem(ProblemCode code, String detail, List<FieldError> errors,
                                                  UUID documentId, WebRequest request) {
        var problem = ProblemDetail.forStatusAndDetail(HttpStatusCode.valueOf(code.status()), detail);
        problem.setType(URI.create(code.type()));
        problem.setTitle(code.title());
        if (request instanceof ServletWebRequest servletRequest) {
            problem.setInstance(URI.create(servletRequest.getRequest().getRequestURI()));
        }
        if (!errors.isEmpty()) {
            problem.setProperty("errors", errors);
        }
        if (documentId != null) {
            problem.setProperty("documentId", documentId);
        }
        return ResponseEntity.status(code.status()).contentType(MediaType.APPLICATION_PROBLEM_JSON).body(problem);
    }

    /** Where the body went wrong — e.g. {@code ucas[0].useCaseId} — and how. */
    private static FieldError fieldError(JacksonException e) {
        var path = new StringBuilder();
        for (var reference : e.getPath()) {
            if (reference.getPropertyName() != null) {
                path.append(path.isEmpty() ? "" : ".").append(reference.getPropertyName());
            } else if (reference.getIndex() >= 0) {
                path.append('[').append(reference.getIndex()).append(']');
            }
        }
        var field = path.isEmpty() ? "body" : path.toString();
        if (e instanceof MismatchedInputException mismatch && mismatch.getTargetType() != null
                && mismatch.getTargetType().isEnum()) {
            var values = Arrays.stream(mismatch.getTargetType().getEnumConstants())
                    .map(constant -> constant instanceof UseCase useCase ? useCase.id() : ((Enum<?>) constant).name())
                    .toList();
            return new FieldError(field, "not one of %s".formatted(values));
        }
        var message = e.getOriginalMessage();
        return new FieldError(field, "not valid: " + (message == null ? "unreadable" : message.lines().findFirst().orElse("")));
    }
}
