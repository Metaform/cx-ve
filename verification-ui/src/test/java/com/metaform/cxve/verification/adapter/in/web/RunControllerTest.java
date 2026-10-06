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
 * its dataspace and use case, and its member id is mandatory. Nothing in this environment may
 * invent a participant's identifier — it issues the member credential for exactly the value given,
 * and an external system's is agreed with its operator — so a run with none is refused here rather
 * than given a derived placeholder downstream.
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
    void start_rejectsAMissingMemberId() throws Exception {
        mvc.perform(post("/api/runs").contentType(MediaType.APPLICATION_JSON).content("""
                        {"dataspace": "catena-x", "useCase": "ccm", "name": "Acme Corp", "shortName": "acme"}"""))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(runService);
    }

    @Test
    void start_rejectsABlankMemberId() throws Exception {
        mvc.perform(post("/api/runs").contentType(MediaType.APPLICATION_JSON).content("""
                        {"dataspace": "catena-x", "useCase": "ccm", "shortName": "acme", "memberId": "   "}"""))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(runService);
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

    /** An external run declares a DID, and still has to declare the member id agreed with its operator. */
    @Test
    void start_rejectsAnExternalRunWithoutAMemberId() throws Exception {
        mvc.perform(post("/api/runs").contentType(MediaType.APPLICATION_JSON).content("""
                        {"dataspace": "catena-x", "useCase": "ccm", "did": "did:web:sut.example.com:vendor"}"""))
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
