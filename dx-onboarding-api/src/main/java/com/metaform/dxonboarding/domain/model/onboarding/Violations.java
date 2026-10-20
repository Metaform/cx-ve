package com.metaform.dxonboarding.domain.model.onboarding;

import com.metaform.dxonboarding.domain.FieldError;
import java.util.ArrayList;
import java.util.List;

/** Collects the field problems of a check, in the order found. */
final class Violations {

    private final List<FieldError> errors = new ArrayList<>();

    void add(String field, String message) {
        errors.add(new FieldError(field, message));
    }

    void required(String field, Object value) {
        if (value == null || value instanceof String text && text.isBlank()) {
            add(field, "required");
        }
    }

    void maxLength(String field, String value, int max) {
        if (value != null && value.length() > max) {
            add(field, "at most %d characters".formatted(max));
        }
    }

    void maxItems(String field, List<?> values, int max) {
        if (values.size() > max) {
            add(field, "at most %d entries".formatted(max));
        }
    }

    void affirmed(String field, Boolean value) {
        if (!Boolean.TRUE.equals(value)) {
            add(field, "must be affirmed");
        }
    }

    List<FieldError> list() {
        return List.copyOf(errors);
    }
}
