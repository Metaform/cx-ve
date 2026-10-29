package com.metaform.cxve.verification.adapter.in.web;

import com.metaform.cxve.verification.application.VerificationException;
import com.metaform.cxve.verification.application.VerificationParticipantService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** A failed ensure tells the dashboard why — a bare 500 left the operator to dig through the logs. */
@WebMvcTest(controllers = VerificationParticipantController.class)
class VerificationParticipantControllerTest {

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private VerificationParticipantService participantService;

    @Test
    void aFailedEnsureAnswersWithItsReason() throws Exception {
        when(participantService.ensure("decade-x"))
                .thenThrow(new VerificationException("asset id 'ccm-inbox-verification' is taken by another participant context"));

        mvc.perform(post("/api/verification-participant").param("dataspace", "decade-x"))
                .andExpect(status().isInternalServerError())
                .andExpect(content().string("asset id 'ccm-inbox-verification' is taken by another participant context"));
    }
}
