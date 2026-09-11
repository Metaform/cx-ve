package com.metaform.cxve.hub.adapter.in.web;

import com.metaform.cxve.hub.adapter.out.eventlog.EventlogRepository;
import com.metaform.cxve.hub.domain.model.eventlog.EventSummary;
import com.metaform.cxve.hub.domain.model.eventlog.ParticipantEventlog;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.security.oauth2.server.resource.autoconfigure.web.OAuth2ResourceServerWebSecurityAutoConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Web-slice test of the eventlog read API: JSON shapes (camelCase, not the views' snake_case),
 * the 404 mapping, filter pass-through and the paging clamp. The repository is mocked — its SQL
 * has its own Postgres-backed test. The conditional-bean property is supplied explicitly, since
 * the endpoints only exist when the tracker datasource is configured.
 */
// The resource-server auto-config would activate here (the packaged application.yaml sets a
// jwk-set-uri) but the slice carries no HttpSecurity — and security is irrelevant to this
// slice, so it is excluded and the filter chain disabled.
@WebMvcTest(controllers = EventlogController.class,
        properties = "eventtracker.datasource.url=jdbc:postgresql://unused:5432/unused",
        excludeAutoConfiguration = OAuth2ResourceServerWebSecurityAutoConfiguration.class)
@AutoConfigureMockMvc(addFilters = false)
class EventlogControllerTest {

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private EventlogRepository eventlogRepository;

    private static ParticipantEventlog rollup() {
        return new ParticipantEventlog("proc-1", "ext-1", "BPNLONE000000001", "did:web:one", "pctx-1",
                "COMPLETED", 1,
                List.of(new EventSummary(OffsetDateTime.parse("2026-01-01T10:01:00Z"),
                        "events.onboarding.started", "OnboardingStarted.v1", "onboarding-api", "ev-1")));
    }

    @Test
    void participants_serializeCamelCase() throws Exception {
        when(eventlogRepository.findAll("BPNLONE000000001", null, null)).thenReturn(List.of(rollup()));

        mvc.perform(get("/api/eventlog/participants").param("bpn", "BPNLONE000000001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].processId").value("proc-1"))
                .andExpect(jsonPath("$[0].participantContextId").value("pctx-1"))
                .andExpect(jsonPath("$[0].eventCount").value(1))
                .andExpect(jsonPath("$[0].events[0].subject").value("events.onboarding.started"))
                .andExpect(jsonPath("$[0].events[0].eventId").value("ev-1"))
                .andExpect(jsonPath("$[0].events[0].occurredAt").exists());
    }

    @Test
    void participant_unknownProcessIdIs404() throws Exception {
        when(eventlogRepository.findByProcessId("no-such")).thenReturn(Optional.empty());

        mvc.perform(get("/api/eventlog/participants/no-such"))
                .andExpect(status().isNotFound());
    }

    @Test
    void events_clampOffsetAndLimit() throws Exception {
        when(eventlogRepository.findEvents(eq("proc-1"), anyInt(), anyInt())).thenReturn(List.of());

        mvc.perform(get("/api/eventlog/participants/proc-1/events")
                        .param("offset", "-5").param("limit", "5000"))
                .andExpect(status().isOk());

        verify(eventlogRepository).findEvents("proc-1", 0, 1000);
    }
}
