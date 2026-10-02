package com.metaform.dxonboarding.adapter.in.web;

import com.metaform.dxonboarding.application.ApplicationService;
import com.metaform.dxonboarding.config.SecurityConfig;
import com.metaform.dxonboarding.domain.model.ApplicationRecord;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The API surface: authenticated, validated, scoped to the caller's token subject. */
@WebMvcTest(controllers = ApplicationController.class)
@Import(SecurityConfig.class)
class ApplicationControllerTest {

    private static final String APPLICATION = """
            {"applicationRef": "ref-1", "legalName": "Acme Corp", "decadeXId": "DX-00000001",
             "did": "did:web:acme", "country": "DE", "contactEmail": "ops@acme.example"}""";

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private ApplicationService service;

    @Test
    void submit_acceptsAValidApplicationForTheCaller() throws Exception {
        when(service.submit(eq("hub"), any())).thenReturn(
                new ApplicationRecord("app-1", "hub", null, Instant.now(), null));

        mvc.perform(post("/api/v1/applications").with(jwt().jwt(token -> token.subject("hub")))
                        .contentType(MediaType.APPLICATION_JSON).content(APPLICATION))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.applicationId").value("app-1"));
    }

    @Test
    void submit_refusesAMalformedDecadeXId() throws Exception {
        mvc.perform(post("/api/v1/applications").with(jwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(APPLICATION.replace("DX-00000001", "BPNL0000000000XY")))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(service);
    }

    @Test
    void submit_requiresABearer() throws Exception {
        mvc.perform(post("/api/v1/applications").contentType(MediaType.APPLICATION_JSON).content(APPLICATION))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(service);
    }

    @Test
    void setWebhook_registersItForTheCaller() throws Exception {
        mvc.perform(put("/api/v1/webhook").with(jwt().jwt(token -> token.subject("hub")))
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"url": "http://hub/cb", "tokenUrl": "http://idp/token",
                                 "clientId": "cb", "clientSecret": "s3cret"}"""))
                .andExpect(status().isNoContent());

        verify(service).registerWebhook(eq("hub"), any());
    }
}
