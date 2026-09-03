package com.metaform.cxve.domain.model;

/**
 * Payload of the status update POSTed to an onboarding service provider's registered callback
 * (the spec's {@code OspRegistrationCallbackData}).
 */
public record OspRegistrationCallbackData(String externalId, RegistrationStatus status, String message) {
}
