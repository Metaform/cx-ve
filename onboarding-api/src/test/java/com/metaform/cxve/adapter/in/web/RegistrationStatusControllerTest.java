package com.metaform.cxve.adapter.in.web;

import com.metaform.cxve.adapter.out.callback.RegistrationStatusService;
import com.metaform.cxve.config.ApiSecurityConfig;
import com.metaform.cxve.domain.model.CallbackRequestData;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Web-layer tests including the security rules of {@link ApiSecurityConfig}: bearer-JWT required,
 * the configure scope on the POST, and the caller identity — {@code act.sub}, the jwtlet claim
 * recording the originating client — as the registration key.
 */
@WebMvcTest(RegistrationStatusController.class)
@Import(ApiSecurityConfig.class)
class RegistrationStatusControllerTest {

    private static final String CALLBACK_PATH = "/api/administration/RegistrationStatus/callback";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private RegistrationStatusService registrationStatusService;

    // Replaces the JWKS-backed decoder bean; the jwt() post-processor injects tokens directly.
    @MockitoBean
    private JwtDecoder jwtDecoder;

    /** A jwtlet-shaped token: sub is the (shared) participant context, act.sub the client. */
    private static JwtRequestPostProcessor client1() {
        return jwt().jwt(j -> j.subject("osp").claim("act", Map.of("sub", "client-1")));
    }

    @Test
    void getCallback_returnsTheCallersCallbackData() throws Exception {
        when(registrationStatusService.getCallbackAddress("client-1")).thenReturn(
                new CallbackRequestData(
                        "https://osp.example/callback",
                        "https://auth.example/token",
                        "client-1",
                        "secret-1"));

        mockMvc.perform(get(CALLBACK_PATH).with(client1()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.callbackUrl").value("https://osp.example/callback"))
                .andExpect(jsonPath("$.authUrl").value("https://auth.example/token"))
                .andExpect(jsonPath("$.clientId").value("client-1"))
                .andExpect(jsonPath("$.clientSecret").value("secret-1"));
    }

    @Test
    void getCallback_withoutAnActClaim_fallsBackToTheTokenSubject() throws Exception {
        // A token without an RFC 8693 actor (not every issuer is an exchange) still has an
        // identity: the subject itself.
        when(registrationStatusService.getCallbackAddress("sub-1")).thenReturn(
                new CallbackRequestData("https://osp.example/callback", null, null, null));

        mockMvc.perform(get(CALLBACK_PATH).with(jwt().jwt(j -> j.subject("sub-1"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.callbackUrl").value("https://osp.example/callback"));
    }

    @Test
    void setCallback_registersUnderTheCallersIdentity_notThePayloads() throws Exception {
        // The payload's clientId is the OSP's OAuth2 client for the outbound callback call — an
        // attacker-controlled field. The registration key must be the AUTHENTICATED identity, or
        // any caller could overwrite any other client's callback.
        mockMvc.perform(post(CALLBACK_PATH)
                        .with(client1().authorities(new SimpleGrantedAuthority(ApiSecurityConfig.CONFIGURE_PARTNER_REGISTRATION)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "callbackUrl": "https://osp.example/callback",
                                  "authUrl": "https://auth.example/token",
                                  "clientId": "somebody-else",
                                  "clientSecret": "secret-1"
                                }
                                """))
                .andExpect(status().isNoContent());

        verify(registrationStatusService).setCallbackAddress(
                "client-1",
                new CallbackRequestData(
                        "https://osp.example/callback",
                        "https://auth.example/token",
                        "somebody-else",
                        "secret-1"));
    }

    @Test
    void setCallback_withoutTheConfigureScope_is403() throws Exception {
        // Reading is open to any authenticated client; REGISTERING redirects onboarding outcome
        // data to a third party and is gated on configure_partner_registration.
        mockMvc.perform(post(CALLBACK_PATH)
                        .with(client1())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "callbackUrl": "https://osp.example/callback" }
                                """))
                .andExpect(status().isForbidden());

        verifyNoInteractions(registrationStatusService);
    }

    @Test
    void withoutABearerToken_is401() throws Exception {
        mockMvc.perform(get(CALLBACK_PATH))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post(CALLBACK_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(registrationStatusService);
    }
}
