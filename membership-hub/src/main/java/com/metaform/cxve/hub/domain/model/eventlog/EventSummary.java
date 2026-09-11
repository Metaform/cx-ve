package com.metaform.cxve.hub.domain.model.eventlog;

import com.fasterxml.jackson.annotation.JsonAlias;
import java.time.OffsetDateTime;

/**
 * A compact event entry from the {@code participant_eventlog} view's {@code events} JSONB array.
 * The aliases match that array's snake_case keys; the API response uses this record's camelCase.
 */
public record EventSummary(
        @JsonAlias("occurred_at") OffsetDateTime occurredAt,
        String subject,
        String type,
        String source,
        @JsonAlias("event_id") String eventId) {
}
