package com.metaform.cxve.hub.adapter.out.eventlog;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.metaform.cxve.hub.domain.model.eventlog.EventDetail;
import com.metaform.cxve.hub.domain.model.eventlog.EventSummary;
import com.metaform.cxve.hub.domain.model.eventlog.ParticipantEventlog;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Read-only queries against the compliance tracker's views. All correlation semantics (which
 * event belongs to which participant, the identity linking, the time fencing of dead registration
 * attempts) live in the views themselves — this class adds transport, not interpretation, so the
 * tracker remains the single place those rules are defined.
 */
@Repository
@ConditionalOnProperty(prefix = "eventtracker.datasource", name = "url")
public class EventlogRepository {

    private static final String ROLLUP_SELECT =
            "SELECT process_id, external_id, bpn, did, participant_context_id, state, event_count, events "
                    + "FROM participant_eventlog";
    private static final String EVENT_SELECT =
            "SELECT source, event_id, subject, type, occurred_at, recorded_at, envelope, "
                    + "participant_context_id, holder_did, bpn, onboarding_process_id "
                    + "FROM participant_event";

    private final JdbcClient jdbc;
    // The web layer's mapper: it has the JavaTimeModule registered, and the parsed records go
    // straight back out through it.
    private final ObjectMapper objectMapper;

    public EventlogRepository(@Qualifier("eventTrackerJdbcClient") JdbcClient jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    /** All participant rollups, optionally filtered; any filter may be null. */
    public List<ParticipantEventlog> findAll(String bpn, String externalId, String did) {
        var conditions = new ArrayList<String>();
        var params = new LinkedHashMap<String, Object>();
        if (bpn != null) {
            conditions.add("bpn = :bpn");
            params.put("bpn", bpn);
        }
        if (externalId != null) {
            conditions.add("external_id = :externalId");
            params.put("externalId", externalId);
        }
        if (did != null) {
            conditions.add("did = :did");
            params.put("did", did);
        }
        var sql = ROLLUP_SELECT
                + (conditions.isEmpty() ? "" : " WHERE " + String.join(" AND ", conditions))
                // the view exposes no timestamp to order by — deterministic beats meaningful here
                + " ORDER BY process_id";
        var spec = jdbc.sql(sql);
        params.forEach(spec::param);
        return spec.query((rs, rowNum) -> toRollup(rs)).list();
    }

    public Optional<ParticipantEventlog> findByProcessId(String processId) {
        return jdbc.sql(ROLLUP_SELECT + " WHERE process_id = :processId")
                .param("processId", processId)
                .query((rs, rowNum) -> toRollup(rs))
                .optional();
    }

    /** A page of full events (envelope included) for one participant, in event-time order. */
    public List<EventDetail> findEvents(String processId, int offset, int limit) {
        return jdbc.sql(EVENT_SELECT + " WHERE participant_id = :processId "
                        + "ORDER BY COALESCE(occurred_at, recorded_at) LIMIT :limit OFFSET :offset")
                .param("processId", processId)
                .param("limit", limit)
                .param("offset", offset)
                .query((rs, rowNum) -> toDetail(rs))
                .list();
    }

    private ParticipantEventlog toRollup(ResultSet rs) throws SQLException {
        return new ParticipantEventlog(
                rs.getString("process_id"),
                rs.getString("external_id"),
                rs.getString("bpn"),
                rs.getString("did"),
                rs.getString("participant_context_id"),
                rs.getString("state"),
                rs.getLong("event_count"),
                parseEvents(rs.getString("events")));
    }

    private EventDetail toDetail(ResultSet rs) throws SQLException {
        return new EventDetail(
                rs.getString("source"),
                rs.getString("event_id"),
                rs.getString("subject"),
                rs.getString("type"),
                rs.getObject("occurred_at", OffsetDateTime.class),
                rs.getObject("recorded_at", OffsetDateTime.class),
                readEnvelope(rs.getString("envelope")),
                rs.getString("participant_context_id"),
                rs.getString("holder_did"),
                rs.getString("bpn"),
                rs.getString("onboarding_process_id"));
    }

    private List<EventSummary> parseEvents(String json) {
        // The view's jsonb_agg is FILTERed, so a participant with no attributed events yet
        // (onboarding just started) comes back as SQL NULL, not an empty array.
        if (json == null) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<EventSummary>>() { });
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to parse a participant_eventlog events array", e);
        }
    }

    private JsonNode readEnvelope(String json) {
        try {
            return objectMapper.readTree(json);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to parse a stored event envelope", e);
        }
    }
}
