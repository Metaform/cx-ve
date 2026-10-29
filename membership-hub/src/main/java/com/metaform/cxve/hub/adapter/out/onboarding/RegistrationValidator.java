package com.metaform.cxve.hub.adapter.out.onboarding;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.metaform.cxve.hub.domain.port.DataspaceOnboarding.InvalidRegistrationException;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import java.util.Comparator;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Reads a member request's untyped {@code registration} object into a dataspace's typed record
 * and bean-validates it — the shared half of every
 * {@link com.metaform.cxve.hub.domain.port.DataspaceOnboarding#validate}. Unknown properties are
 * refused, so a payload meant for another dataspace does not slip through half-read.
 */
public class RegistrationValidator {

    // A plain mapper, like the JPA store's: the registration's shape is independent of the web
    // layer's JSON configuration.
    private final ObjectMapper objectMapper = new ObjectMapper()
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    private final Validator validator;

    public RegistrationValidator(Validator validator) {
        this.validator = validator;
    }

    /** @throws InvalidRegistrationException when the object does not read or does not validate */
    public <T> T read(Map<String, Object> registration, Class<T> type, String dataspace) {
        T typed;
        try {
            typed = objectMapper.convertValue(registration, type);
        } catch (IllegalArgumentException e) {
            throw new InvalidRegistrationException("Invalid %s registration: %s"
                    .formatted(dataspace, e.getCause() != null ? e.getCause().getMessage() : e.getMessage()));
        }
        var violations = validator.validate(typed);
        if (!violations.isEmpty()) {
            throw new InvalidRegistrationException("Invalid %s registration: %s".formatted(dataspace,
                    violations.stream()
                            .sorted(Comparator.comparing(v -> v.getPropertyPath().toString()))
                            .map(RegistrationValidator::describe)
                            .collect(Collectors.joining("; "))));
        }
        return typed;
    }

    private static String describe(ConstraintViolation<?> violation) {
        return "registration.%s %s".formatted(violation.getPropertyPath(), violation.getMessage());
    }
}
