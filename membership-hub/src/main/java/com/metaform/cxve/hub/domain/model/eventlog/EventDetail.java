package com.metaform.cxve.hub.domain.model.eventlog;

import java.time.OffsetDateTime;
import tools.jackson.databind.JsonNode;

/**
 * A full ledger event attributed to a participant, from the compliance tracker's
 * {@code participant_event} view — including the raw CloudEvents envelope and whichever of the
 * four correlation keys the event carried.
 */
public record EventDetail(
        String source,
        String eventId,
        String subject,
        String type,
        OffsetDateTime occurredAt,
        OffsetDateTime recordedAt,
        JsonNode envelope,
        String participantContextId,
        String holderDid,
        String bpn,
        String onboardingProcessId) {
}
