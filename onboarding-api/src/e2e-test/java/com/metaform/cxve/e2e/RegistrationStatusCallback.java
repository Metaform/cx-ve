package com.metaform.cxve.e2e;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * E2e-local mirror of the registration-status callback payload (the app's
 * {@code OspRegistrationCallbackData}): deliberately no more than the OSP contract — which
 * registration ({@code externalId}), where it landed ({@code state}: SUBMITTED, CONFIRMED or
 * REJECTED) and, on rejection, why ({@code message}). Keep in sync with the app manually, like
 * {@link NewParticipantData}.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record RegistrationStatusCallback(String externalId, String state, String message) {
}
