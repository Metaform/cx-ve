package com.metaform.cxve.adapter.out.nats;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * The {@code data} payload of an IdentityHub participant-context CloudEvent (the EDC
 * {@code ParticipantContextEvent} fields). The manifest carries much more (keys, service endpoints);
 * only the identifiers needed for correlation are mapped.
 *
 * <p><b>Correlation note:</b> the manifest {@code did} is the identifier this app supplied to the
 * tenant manager at provisioning time (recorded on the process as holderId), so it is the preferred
 * correlation key; {@code participantContextId} is minted downstream and only used as a fallback
 * for the same holderId-equals-DID assumption documented on {@link IssuanceEventData}.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ParticipantContextEventData(
        String participantContextId,
        Manifest manifest
) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Manifest(String did) {
    }

    /** The id used to correlate the event back to an onboarding, or null if the event carries none. */
    public String correlationId() {
        if (manifest != null && manifest.did() != null && !manifest.did().isBlank()) {
            return manifest.did();
        }
        return participantContextId;
    }
}
