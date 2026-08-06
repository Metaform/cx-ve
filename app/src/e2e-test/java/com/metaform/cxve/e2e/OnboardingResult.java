package com.metaform.cxve.e2e;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * E2e-local mirror of the registration-status callback payload (the app serializes its
 * {@code OnboardingProcess}). {@code holderId} is the participant DID, {@code participantContextId}
 * the EDC participant context. Keep in sync with the app manually, like {@link NewParticipantData}.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record OnboardingResult(
        String id,
        String externalId,
        String state,
        String bpn,
        String participantProfileId,
        String holderId,
        String failureReason,
        String holderProcessId,
        String tenantId,
        String participantContextId
) {}
