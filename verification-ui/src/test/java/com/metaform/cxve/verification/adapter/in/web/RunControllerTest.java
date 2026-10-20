package com.metaform.cxve.verification.adapter.in.web;

import com.metaform.cxve.verification.application.DataspaceCatalog;
import com.metaform.cxve.verification.application.RunService;
import com.metaform.cxve.verification.domain.model.RunStep;
import com.metaform.cxve.verification.domain.model.VerificationRun;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Web-slice test of the request-shape rules the run flow cannot enforce for itself: a run names
 * its dataspace and use case. Whether it must also name a member id depends on the dataspace —
 * mandatory in Catena-X, refused in DECADE-X, which assigns it — so that is the catalog's call: a
 * run without one reaches the service as is, and the service's refusal is a 400.
 */
@WebMvcTest(controllers = RunController.class)
class RunControllerTest {

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private RunService runService;

    @Test
    void start_acceptsARunOfAUseCaseInADataspace() throws Exception {
        when(runService.start(any(), any(), any(), any(), any(), any())).thenReturn(
                new VerificationRun("r1", "catena-x", "ccm", "Acme Corp", "acme", "BPNLACME00000001", "DEACME0001",
                        null, RunStep.MANAGED).snapshot());

        mvc.perform(post("/api/runs").contentType(MediaType.APPLICATION_JSON).content("""
                        {"dataspace": "catena-x", "useCase": "ccm",
                         "name": "Acme Corp", "shortName": "acme", "memberId": "BPNLACME00000001"}"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.dataspace").value("catena-x"))
                .andExpect(jsonPath("$.useCase").value("ccm"))
                .andExpect(jsonPath("$.participant.memberId").value("BPNLACME00000001"));

        // the declared member id reaches the service verbatim — nothing between here and the run
        // substitutes or normalizes it
        verify(runService).start("catena-x", "ccm", "Acme Corp", "acme", "BPNLACME00000001", null);
    }

    @Test
    void start_leavesAMissingMemberIdToTheCatalog() throws Exception {
        when(runService.start(any(), any(), any(), any(), any(), any())).thenThrow(
                new DataspaceCatalog.InvalidRunRequestException("The participant's BPN is required"));

        mvc.perform(post("/api/runs").contentType(MediaType.APPLICATION_JSON).content("""
                        {"dataspace": "catena-x", "useCase": "ccm", "name": "Acme Corp", "shortName": "acme"}"""))
                .andExpect(status().isBadRequest());

        verify(runService).start("catena-x", "ccm", "Acme Corp", "acme", null, null);
    }

    /** DECADE-X assigns the member id on onboarding: the run starts without one. */
    @Test
    void start_acceptsARunWithoutAMemberIdWhereTheDataspaceAssignsIt() throws Exception {
        when(runService.start(any(), any(), any(), any(), any(), any())).thenReturn(
                new VerificationRun("r1", "decade-x", "ccm", "Acme Corp", "acme", null, "DEACME0001",
                        null, RunStep.MANAGED).snapshot());

        mvc.perform(post("/api/runs").contentType(MediaType.APPLICATION_JSON).content("""
                        {"dataspace": "decade-x", "useCase": "ccm", "name": "Acme Corp", "shortName": "acme"}"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.participant.memberId").isEmpty());

        verify(runService).start("decade-x", "ccm", "Acme Corp", "acme", null, null);
    }

    @Test
    void start_rejectsARunWithoutDataspaceOrUseCase() throws Exception {
        mvc.perform(post("/api/runs").contentType(MediaType.APPLICATION_JSON).content("""
                        {"useCase": "ccm", "memberId": "BPNLACME00000001"}"""))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/runs").contentType(MediaType.APPLICATION_JSON).content("""
                        {"dataspace": "catena-x", "memberId": "BPNLACME00000001"}"""))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(runService);
    }

    @Test
    void start_mapsARequestTheCatalogRefusesTo400() throws Exception {
        when(runService.start(any(), any(), any(), any(), any(), any())).thenThrow(
                new DataspaceCatalog.InvalidRunRequestException("Traceability in Catena-X cannot be verified yet"));

        mvc.perform(post("/api/runs").contentType(MediaType.APPLICATION_JSON).content("""
                        {"dataspace": "catena-x", "useCase": "traceability", "memberId": "BPNLACME00000001"}"""))
                .andExpect(status().isBadRequest());
    }

    @Test
    void start_rejectsAnEmptyBody() throws Exception {
        mvc.perform(post("/api/runs").contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(runService);
    }
}
