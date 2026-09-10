package com.metaform.cxve.hub.domain.model.eventlog;

import java.util.List;

/**
 * One participant's rollup from the compliance tracker's {@code participant_eventlog} view: all
 * three identities (BPN, DID, participant context id) unified on one row, with the participant's
 * whole recorded history as a time-ordered list of compact event summaries. The full envelopes
 * stay behind {@code /events} — inlining them here would balloon every response with credential
 * payloads.
 */
public record ParticipantEventlog(
        String processId,
        String externalId,
        String bpn,
        String did,
        String participantContextId,
        String state,
        long eventCount,
        List<EventSummary> events) {
}
