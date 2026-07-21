package com.metaform.cxve.infrastructure.nats;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * The {@code data} payload of an IdentityHub issuance CloudEvent (the EDC {@code IssuanceEvent}
 * fields). Per-event extras (e.g. the delivered credential collection) are ignored here — we only
 * need the correlation identifiers.
 *
 * <p><b>Correlation note:</b> all of these ids are minted downstream of onboarding. The Onboarding
 * API only ever supplies a participant DID to the tenant manager, so mapping an event back to an
 * onboarding relies on {@code holderId} carrying that DID. This is the assumption to confirm against
 * a live IdentityHub; if it does not hold, correlation must move to whichever id the process can
 * record at provisioning time.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record IssuanceEventData(
        String holderId,
        String issuerParticipantContextId,
        String holderProcessId
) {
}
