package com.metaform.cxve.domain.model;

/**
 * Domain-facing result of provisioning a participant (the "wallet") via {@code WalletService} — a
 * provider-neutral view over whatever the underlying adapter returns. {@code participantContextId}
 * and {@code holderProcessId} are assigned asynchronously and may be {@code null} until provisioning
 * completes; {@code error} signals a failed deployment.
 */
public record ProvisionedParticipant(
        String id,
        String identifier,
        String tenantId,
        String participantContextId,
        String holderProcessId,
        boolean error
) {
}
