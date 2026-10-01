package com.metaform.cxve.verification.adapter.in.web;

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
 * Web-slice test of the one request-shape rule the run flow cannot enforce for itself: the BPN is
 * mandatory. Nothing in this environment may invent a participant's identifier — it issues the
 * BpnCredential for exactly the value given, and an external system's is agreed with its operator
 * — so a run with none is refused here rather than given a derived placeholder downstream.
 */
@WebMvcTest(controllers = RunController.class)
class RunControllerTest {

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private RunService runService;

    @Test
    void start_acceptsARunCarryingABpn() throws Exception {
        when(runService.start(any(), any(), any(), any())).thenReturn(
                new VerificationRun("r1", "Acme Corp", "acme", "BPNLACME00000001", "DEACME0001",
                        null, RunStep.MANAGED).snapshot());

        mvc.perform(post("/api/runs").contentType(MediaType.APPLICATION_JSON).content("""
                        {"name": "Acme Corp", "shortName": "acme", "bpn": "BPNLACME00000001"}"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.participant.bpn").value("BPNLACME00000001"));

        // the declared BPN reaches the service verbatim — nothing between here and the run
        // substitutes or normalizes it
        verify(runService).start("Acme Corp", "acme", "BPNLACME00000001", null);
    }

    @Test
    void start_rejectsAMissingBpn() throws Exception {
        mvc.perform(post("/api/runs").contentType(MediaType.APPLICATION_JSON).content("""
                        {"name": "Acme Corp", "shortName": "acme"}"""))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(runService);
    }

    @Test
    void start_rejectsABlankBpn() throws Exception {
        mvc.perform(post("/api/runs").contentType(MediaType.APPLICATION_JSON).content("""
                        {"shortName": "acme", "bpn": "   "}"""))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(runService);
    }

    /** An external run declares a DID, and still has to declare the BPN agreed with its operator. */
    @Test
    void start_rejectsAnExternalRunWithoutABpn() throws Exception {
        mvc.perform(post("/api/runs").contentType(MediaType.APPLICATION_JSON).content("""
                        {"did": "did:web:sut.example.com:vendor"}"""))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(runService);
    }

    @Test
    void start_rejectsAnEmptyBody() throws Exception {
        mvc.perform(post("/api/runs").contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(runService);
    }
}
