package com.metaform.cxve.hub.adapter.in.web;

import com.metaform.cxve.hub.adapter.out.eventlog.EventlogRepository;
import com.metaform.cxve.hub.domain.model.eventlog.EventDetail;
import com.metaform.cxve.hub.domain.model.eventlog.ParticipantEventlog;
import java.util.List;
import java.util.NoSuchElementException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Read-only view of the participant eventlog the compliance tracker writes: who was onboarded,
 * and every recorded event attributed to them (onboarding, identity, negotiation, transfer,
 * certificate exchange). Unauthenticated by design, like the rest of the hub's operator surface
 * — and, unlike {@code GET /api/members/{id}}, reading here never triggers outbound calls.
 * Present only when {@code eventtracker.datasource.url} is configured.
 */
@RestController
@RequestMapping("/api/eventlog")
@ConditionalOnProperty(prefix = "eventtracker.datasource", name = "url")
public class EventlogController {

    private static final int MAX_PAGE_SIZE = 1000;

    private final EventlogRepository eventlogRepository;

    public EventlogController(EventlogRepository eventlogRepository) {
        this.eventlogRepository = eventlogRepository;
    }

    /** All participant rollups, optionally filtered by any of the identities. */
    @GetMapping("/participants")
    public List<ParticipantEventlog> participants(@RequestParam(required = false) String bpn,
                                                  @RequestParam(required = false) String externalId,
                                                  @RequestParam(required = false) String did) {
        return eventlogRepository.findAll(bpn, externalId, did);
    }

    /** One participant's rollup, keyed by its onboarding process id. */
    @GetMapping("/participants/{processId}")
    public ParticipantEventlog participant(@PathVariable String processId) {
        return eventlogRepository.findByProcessId(processId)
                .orElseThrow(() -> new NoSuchElementException("No participant with process id " + processId));
    }

    /** A page of the participant's full events, envelopes included, in event-time order. */
    @GetMapping("/participants/{processId}/events")
    public List<EventDetail> events(@PathVariable String processId,
                                    @RequestParam(defaultValue = "0") int offset,
                                    @RequestParam(defaultValue = "100") int limit) {
        return eventlogRepository.findEvents(processId, Math.max(offset, 0),
                Math.min(Math.max(limit, 1), MAX_PAGE_SIZE));
    }

    @ExceptionHandler(NoSuchElementException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public String notFound(NoSuchElementException e) {
        return e.getMessage();
    }
}
