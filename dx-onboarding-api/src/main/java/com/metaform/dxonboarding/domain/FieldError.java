package com.metaform.dxonboarding.domain;

/**
 * One problem with a request's data, as a problem body's {@code errors} lists it.
 *
 * @param field the field's path, e.g. {@code legalEntity.legalName} or {@code ucas[1].useCaseId}
 */
public record FieldError(String field, String message) {
}
