package com.metaform.cxve.adapter.in.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.metaform.cxve.adapter.out.callback.RegistrationStatusService;
import com.metaform.cxve.config.ApiSecurityConfig;
import com.metaform.cxve.domain.model.CallbackRequestData;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Web-layer tests including the security rules of {@link ApiSecurityConfig}: bearer-JWT plus the
 * configure_partner_registration scope on every administration endpoint, and the caller identity
 * — {@code act.sub}, the jwtlet claim recording the originating client — as the registration key.
 */
@WebMvcTest(RegistrationStatusController.class)
@Import(ApiSecurityConfig.class)
class RegistrationStatusControllerTest {

    private static final String CALLBACK_PATH = "/api/administration/registrationstatus/callback";

    private static final String VALID_BODY = """
            {
              "callbackUrl": "https://osp.example/callback",
              "authUrl": "https://auth.example/token",
              "clientId": "somebody-else",
              "clientSecret": "secret-1"
            }
            """;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private RegistrationStatusService registrationStatusService;

    // Replaces the JWKS-backed decoder bean; the jwt() post-processor injects tokens directly.
    @MockitoBean
    private JwtDecoder jwtDecoder;

    /** A jwtlet-shaped token: sub is the (shared) participant context, act.sub the client. */
    private static JwtRequestPostProcessor client1() {
        return jwt().jwt(j -> j.subject("osp").claim("act", Map.of("sub", "client-1")))
                .authorities(new SimpleGrantedAuthority(ApiSecurityConfig.CONFIGURE_PARTNER_REGISTRATION));
    }

    /** The same caller without the configure scope — every administration endpoint rejects it. */
    private static JwtRequestPostProcessor scopelessClient1() {
        return jwt().jwt(j -> j.subject("osp").claim("act", Map.of("sub", "client-1")));
    }

    @Test
    void getCallback_returnsTheCallersCallbackData_withoutTheSecret() throws Exception {
        when(registrationStatusService.getCallbackAddress("client-1")).thenReturn(
                new CallbackRequestData(
                        "https://osp.example/callback",
                        "https://auth.example/token",
                        "client-1",
                        "secret-1"));

        // The spec's GET response deliberately excludes the client secret — write-only.
        mockMvc.perform(get(CALLBACK_PATH).with(client1()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.callbackUrl").value("https://osp.example/callback"))
                .andExpect(jsonPath("$.authUrl").value("https://auth.example/token"))
                .andExpect(jsonPath("$.clientId").value("client-1"))
                .andExpect(jsonPath("$.clientSecret").doesNotExist());
    }

    @Test
    void getCallback_withoutARegistration_returns200WithAnEmptyBody() throws Exception {
        // "Empty if not configured" per spec §2.2.5.
        when(registrationStatusService.getCallbackAddress("client-1")).thenReturn(null);

        mockMvc.perform(get(CALLBACK_PATH).with(client1()))
                .andExpect(status().isOk())
                .andExpect(content().string(""));
    }

    @Test
    void getCallback_withoutAnActClaim_fallsBackToTheTokenSubject() throws Exception {
        // A token without an RFC 8693 actor (not every issuer is an exchange) still has an
        // identity: the subject itself.
        when(registrationStatusService.getCallbackAddress("sub-1")).thenReturn(
                new CallbackRequestData("https://osp.example/callback", "https://auth.example/token", "c", "s"));

        mockMvc.perform(get(CALLBACK_PATH).with(jwt().jwt(j -> j.subject("sub-1"))
                        .authorities(new SimpleGrantedAuthority(ApiSecurityConfig.CONFIGURE_PARTNER_REGISTRATION))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.callbackUrl").value("https://osp.example/callback"));
    }

    @Test
    void setCallback_registersUnderTheCallersIdentity_notThePayloads() throws Exception {
        // The payload's clientId is the OSP's OAuth2 client for the outbound callback call — an
        // attacker-controlled field. The registration key must be the AUTHENTICATED identity, or
        // any caller could overwrite any other client's callback.
        mockMvc.perform(post(CALLBACK_PATH)
                        .with(client1())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isNoContent());

        verify(registrationStatusService).setCallbackAddress(
                "client-1",
                new CallbackRequestData(
                        "https://osp.example/callback",
                        "https://auth.example/token",
                        "somebody-else",
                        "secret-1"));
    }

    @ParameterizedTest
    @ValueSource(strings = { "callbackUrl", "authUrl", "clientId", "clientSecret" })
    void setCallback_withAMissingField_returns400(String field) throws Exception {
        // All four fields are Mandatory per spec §2.2.4 — a partial configuration the outbound
        // authentication cannot work with must not be stored.
        var payload = (ObjectNode) MAPPER.readTree(VALID_BODY);
        payload.remove(field);

        mockMvc.perform(post(CALLBACK_PATH)
                        .with(client1())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload.toString()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(field + ": must not be blank"));

        verifyNoInteractions(registrationStatusService);
    }

    @Test
    void setCallback_withoutTheConfigureScope_is403() throws Exception {
        mockMvc.perform(post(CALLBACK_PATH)
                        .with(scopelessClient1())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isForbidden());

        verifyNoInteractions(registrationStatusService);
    }

    @Test
    void getCallback_withoutTheConfigureScope_is403() throws Exception {
        // Reading used to be open to any authenticated client; CX-0009 declares the role on
        // every CSP-B endpoint, the read included.
        mockMvc.perform(get(CALLBACK_PATH).with(scopelessClient1()))
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
