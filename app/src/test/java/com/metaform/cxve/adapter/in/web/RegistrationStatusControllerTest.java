package com.metaform.cxve.adapter.in.web;

import com.metaform.cxve.adapter.out.callback.RegistrationStatusService;
import com.metaform.cxve.domain.model.OnboardingServiceProviderCallbackRequestData;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(RegistrationStatusController.class)
class RegistrationStatusControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private RegistrationStatusService registrationStatusService;

    @Test
    void getCallback_returnsCallbackData() throws Exception {
        when(registrationStatusService.getCallbackAddress()).thenReturn(
                new OnboardingServiceProviderCallbackRequestData(
                        "https://osp.example/callback",
                        "https://auth.example/token",
                        "client-1",
                        "secret-1"));

        mockMvc.perform(get("/api/administration/RegistrationStatus/callback"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.callbackUrl").value("https://osp.example/callback"))
                .andExpect(jsonPath("$.authUrl").value("https://auth.example/token"))
                .andExpect(jsonPath("$.clientId").value("client-1"))
                .andExpect(jsonPath("$.clientSecret").value("secret-1"));
    }

    @Test
    void setCallback_returns204AndDelegatesToService() throws Exception {
        mockMvc.perform(post("/api/administration/RegistrationStatus/callback")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "callbackUrl": "https://osp.example/callback",
                                  "authUrl": "https://auth.example/token",
                                  "clientId": "client-1",
                                  "clientSecret": "secret-1"
                                }
                                """))
                .andExpect(status().isNoContent());

        verify(registrationStatusService).setCallbackAddress(
                new OnboardingServiceProviderCallbackRequestData(
                        "https://osp.example/callback",
                        "https://auth.example/token",
                        "client-1",
                        "secret-1"));
    }
}
