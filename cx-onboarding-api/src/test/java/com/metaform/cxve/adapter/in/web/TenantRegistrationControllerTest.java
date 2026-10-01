package com.metaform.cxve.adapter.in.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.metaform.cxve.application.NetworkService;
import com.metaform.cxve.config.ApiSecurityConfig;
import com.metaform.cxve.domain.CancellationNotAllowedException;
import com.metaform.cxve.domain.DuplicateRegistrationException;
import com.metaform.cxve.domain.model.CompanyRoleId;
import com.metaform.cxve.domain.model.ConsentKind;
import com.metaform.cxve.domain.model.OnboardingProcess;
import com.metaform.cxve.domain.model.OnboardingState;
import com.metaform.cxve.domain.model.OspTenantRegistrationData;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(TenantRegistrationController.class)
@Import(ApiSecurityConfig.class)
class TenantRegistrationControllerTest {

    private static final String TENANT_PATH = "/api/administration/osp/v2/tenant-registration";

    /** Required fields that are lists (@NotEmpty); the rest are strings (@NotBlank). */
    private static final Set<String> LIST_FIELDS = Set.of("uniqueIds", "userDetails", "consents");

    /**
     * A complete payload: every §2.2.2-mandatory field including the three required consent
     * kinds, plus the optional address fields and did.
     */
    private static final String VALID_BODY = """
            {
              "externalId": "tenant-ext-1",
              "name": "Tenant GmbH",
              "city": "Munich",
              "streetName": "Otto-Hahn-Ring",
              "countryAlpha2Code": "DE",
              "region": "BY",
              "shortName": "Tenant",
              "streetNumber": "6",
              "zipCode": "81739",
              "did": "did:web:tenant.example",
              "uniqueIds": [
                { "type": "VAT_ID", "value": "DE987654321" }
              ],
              "userDetails": [
                {
                  "providerId": "prov-9",
                  "firstName": "Jane",
                  "lastName": "Doe",
                  "email": "jane.doe@tenant.example"
                }
              ],
              "consents": [
                { "kind": "CX_OPERATING_MODEL", "fileIds": [] },
                { "kind": "CX_TEN_GOLDEN_RULES", "fileIds": [] },
                { "kind": "CX_DATA_EXCHANGE_GOVERNANCE", "fileIds": [] }
              ]
            }
            """;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private NetworkService networkService;

    // Replaces the JWKS-backed decoder bean; the jwt() post-processor injects tokens directly.
    @MockitoBean
    private JwtDecoder jwtDecoder;

    private static JwtRequestPostProcessor ospClient() {
        return jwt().jwt(j -> j.subject("client-1"))
                .authorities(new SimpleGrantedAuthority(ApiSecurityConfig.CONFIGURE_PARTNER_REGISTRATION));
    }

    /** A least-privilege caller carrying exactly one fine-grained scope. */
    private static JwtRequestPostProcessor clientWithScope(String authority) {
        return jwt().jwt(j -> j.subject("client-1")).authorities(new SimpleGrantedAuthority(authority));
    }

    private static OnboardingProcess process(OnboardingState state) {
        return new OnboardingProcess("proc-1", "tenant-ext-1", state,
                "BPNL0000000000XY", "did:web:tenant.example", null, "client-1");
    }

    @Test
    void registerTenant_withoutABearerToken_is401() throws Exception {
        mockMvc.perform(post(TENANT_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(networkService);
    }

    @Test
    void registerTenant_withoutTheConfigureScope_is403() throws Exception {
        mockMvc.perform(post(TENANT_PATH)
                        .with(jwt().jwt(j -> j.subject("client-1")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isForbidden());

        verifyNoInteractions(networkService);
    }

    @Test
    void registerTenant_returns201WithAnEmptyBody_andDelegates() throws Exception {
        when(networkService.registerTenant(eq("client-1"), org.mockito.ArgumentMatchers.any()))
                .thenReturn("process-1");

        mockMvc.perform(post(TENANT_PATH)
                        .with(ospClient())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isCreated())
                .andExpect(content().string(""));

        var captor = ArgumentCaptor.forClass(OspTenantRegistrationData.class);
        verify(networkService).registerTenant(eq("client-1"), captor.capture());
        var data = captor.getValue();
        assertThat(data.externalId()).isEqualTo("tenant-ext-1");
        assertThat(data.consents()).extracting(c -> c.kind()).containsExactlyInAnyOrder(
                ConsentKind.CX_OPERATING_MODEL, ConsentKind.CX_TEN_GOLDEN_RULES, ConsentKind.CX_DATA_EXCHANGE_GOVERNANCE);

        // The internal mapping onto the shared onboarding flow: role implied, no BPN, auto-submitted.
        var mapped = data.toRegistrationData();
        assertThat(mapped.companyRoles()).containsExactly(CompanyRoleId.ACTIVE_PARTICIPANT);
        assertThat(mapped.bpn()).isNull();
        assertThat(mapped.autoSubmit()).isTrue();
        assertThat(mapped.consents()).hasSize(3);
    }

    @Test
    void registerTenant_withoutTheOptionalFields_is201() throws Exception {
        // region, shortName, streetNumber, streetAdditional, zipCode, did are all Optional —
        // notably region, which §2.2.1 declares Mandatory.
        var payload = validPayload();
        payload.remove("region");
        payload.remove("shortName");
        payload.remove("streetNumber");
        payload.remove("zipCode");
        payload.remove("did");

        mockMvc.perform(post(TENANT_PATH)
                        .with(ospClient())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload.toString()))
                .andExpect(status().isCreated());

        verify(networkService).registerTenant(eq("client-1"), org.mockito.ArgumentMatchers.any());
    }

    @ParameterizedTest
    @ValueSource(strings = { "externalId", "name", "city", "streetName", "countryAlpha2Code",
            "uniqueIds", "userDetails", "consents" })
    void registerTenant_withMissingRequiredField_returns400(String field) throws Exception {
        var payload = validPayload();
        payload.remove(field);
        var expectedMessage = field + ": " + (LIST_FIELDS.contains(field) ? "must not be empty" : "must not be blank");

        mockMvc.perform(post(TENANT_PATH)
                        .with(ospClient())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload.toString()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString(expectedMessage)));

        verifyNoInteractions(networkService);
    }

    @ParameterizedTest
    @ValueSource(strings = { "CX_OPERATING_MODEL", "CX_TEN_GOLDEN_RULES", "CX_DATA_EXCHANGE_GOVERNANCE" })
    void registerTenant_withAMissingRequiredConsentKind_returns400(String missingKind) throws Exception {
        // The spec: consents MUST include all three required kinds (schema: minItems 3 + contains).
        var payload = validPayload();
        var consents = payload.putArray("consents");
        for (var kind : new String[] { "CX_OPERATING_MODEL", "CX_TEN_GOLDEN_RULES", "CX_DATA_EXCHANGE_GOVERNANCE" }) {
            if (!kind.equals(missingKind)) {
                consents.addObject().put("kind", kind);
            }
        }

        mockMvc.perform(post(TENANT_PATH)
                        .with(ospClient())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload.toString()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(
                        org.hamcrest.Matchers.containsString("consents must include")));

        verifyNoInteractions(networkService);
    }

    @Test
    void registerTenant_withAnOtherConsentWithoutFileIds_returns400() throws Exception {
        // "fileIds ... MUST be provided if kind is OTHER" — an OTHER consent needs its documents.
        var payload = validPayload();
        payload.withArray("consents").addObject().put("kind", "OTHER");

        mockMvc.perform(post(TENANT_PATH)
                        .with(ospClient())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload.toString()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(
                        org.hamcrest.Matchers.containsString("fileIds must be provided when kind is OTHER")));

        verifyNoInteractions(networkService);
    }

    @Test
    void registerTenant_withAnOtherConsentCarryingFileIds_is201() throws Exception {
        var payload = validPayload();
        var other = payload.withArray("consents").addObject().put("kind", "OTHER");
        other.putArray("fileIds").add("file-1");

        mockMvc.perform(post(TENANT_PATH)
                        .with(ospClient())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload.toString()))
                .andExpect(status().isCreated());

        verify(networkService).registerTenant(eq("client-1"), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void registerTenant_withAConsentKindOutsideTheEnum_returns400() throws Exception {
        var payload = validPayload();
        payload.withArray("consents").addObject().put("kind", "NOT_A_KIND");

        mockMvc.perform(post(TENANT_PATH)
                        .with(ospClient())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload.toString()))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(networkService);
    }

    @Test
    void registerTenant_withAPathUnsafeExternalId_returns400() throws Exception {
        // externalId doubles as the address of the recovery GET/DELETE — an id the path cannot
        // express would be accepted but permanently unreadable/uncancellable.
        var payload = validPayload();
        payload.put("externalId", "orders/2026/001");

        mockMvc.perform(post(TENANT_PATH)
                        .with(ospClient())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload.toString()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(
                        org.hamcrest.Matchers.containsString("URL-path-safe")));

        verifyNoInteractions(networkService);
    }

    @Test
    void registerTenant_withADuplicateExternalId_returns409() throws Exception {
        when(networkService.registerTenant(eq("client-1"), org.mockito.ArgumentMatchers.any()))
                .thenThrow(new DuplicateRegistrationException("tenant-ext-1"));

        mockMvc.perform(post(TENANT_PATH)
                        .with(ospClient())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value(
                        org.hamcrest.Matchers.containsString("tenant-ext-1")));
    }

    @Test
    void registerTenant_toleratesUnknownProperties() throws Exception {
        // Tolerant reader (additionalProperties: true) — notably §2.2.1-only fields like
        // companyRoles/bpn/autoSubmit are silently ignored here, not rejected.
        var payload = validPayload();
        payload.putArray("companyRoles").add("OPERATOR");
        payload.put("bpn", "BPNL000000000001");
        payload.put("autoSubmit", false);

        mockMvc.perform(post(TENANT_PATH)
                        .with(ospClient())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload.toString()))
                .andExpect(status().isCreated());

        var captor = ArgumentCaptor.forClass(OspTenantRegistrationData.class);
        verify(networkService).registerTenant(eq("client-1"), captor.capture());
        // The mapped role is the implied one, regardless of what the extra property said.
        assertThat(captor.getValue().toRegistrationData().companyRoles())
                .containsExactly(CompanyRoleId.ACTIVE_PARTICIPANT);
    }

    @Test
    void getRegistration_returnsTheRecoveryView() throws Exception {
        // The read is the recovery path for a lost callback: the same applicationStatus/bpnl the
        // callback would have carried, plus the process id.
        when(networkService.getRegistration("client-1", "tenant-ext-1"))
                .thenReturn(process(OnboardingState.COMPLETED));

        mockMvc.perform(get(TENANT_PATH + "/tenant-ext-1").with(ospClient()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.externalId").value("tenant-ext-1"))
                .andExpect(jsonPath("$.processId").value("proc-1"))
                .andExpect(jsonPath("$.applicationStatus").value("CONFIRMED"))
                .andExpect(jsonPath("$.bpnl").value("BPNL0000000000XY"))
                .andExpect(jsonPath("$.did").value("did:web:tenant.example"));
    }

    @Test
    void getRegistration_mapsInFlightStatesToSubmitted() throws Exception {
        when(networkService.getRegistration("client-1", "tenant-ext-1"))
                .thenReturn(process(OnboardingState.BPN_ASSIGNED));

        mockMvc.perform(get(TENANT_PATH + "/tenant-ext-1").with(ospClient()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.applicationStatus").value("SUBMITTED"));
    }

    @Test
    void getRegistration_forAnUnknownExternalId_is404() throws Exception {
        when(networkService.getRegistration("client-1", "nope"))
                .thenThrow(new NoSuchElementException("No registration with externalId 'nope' for this client"));

        mockMvc.perform(get(TENANT_PATH + "/nope").with(ospClient()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value(
                        org.hamcrest.Matchers.containsString("nope")));
    }

    @Test
    void listRegistrations_returnsTheCallersRegistrations_filterable() throws Exception {
        when(networkService.listRegistrations("client-1")).thenReturn(List.of(
                process(OnboardingState.COMPLETED),
                new OnboardingProcess("proc-2", "tenant-ext-2", OnboardingState.VALIDATED, null, null, null, "client-1")));

        mockMvc.perform(get(TENANT_PATH).with(ospClient()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));

        mockMvc.perform(get(TENANT_PATH).param("status", "CONFIRMED").with(ospClient()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].externalId").value("tenant-ext-1"));
    }

    @Test
    void cancelRegistration_returns204() throws Exception {
        when(networkService.cancelRegistration("client-1", "tenant-ext-1"))
                .thenReturn(process(OnboardingState.CANCELLED));

        mockMvc.perform(delete(TENANT_PATH + "/tenant-ext-1").with(ospClient()))
                .andExpect(status().isNoContent());

        verify(networkService).cancelRegistration("client-1", "tenant-ext-1");
    }

    @Test
    void cancelRegistration_forAnUnknownExternalId_is404() throws Exception {
        when(networkService.cancelRegistration("client-1", "nope"))
                .thenThrow(new NoSuchElementException("No registration with externalId 'nope' for this client"));

        mockMvc.perform(delete(TENANT_PATH + "/nope").with(ospClient()))
                .andExpect(status().isNotFound());
    }

    @Test
    void cancelRegistration_ofATerminalRegistration_is409() throws Exception {
        when(networkService.cancelRegistration("client-1", "tenant-ext-1"))
                .thenThrow(new CancellationNotAllowedException("tenant-ext-1", OnboardingState.COMPLETED));

        mockMvc.perform(delete(TENANT_PATH + "/tenant-ext-1").with(ospClient()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value(
                        org.hamcrest.Matchers.containsString("COMPLETED")));
    }

    @Test
    void fineGrainedScopes_splitReadAndWrite() throws Exception {
        // Least privilege per the scope model: registration:write creates and cancels but cannot
        // read; registration:read reads but cannot create or cancel. The umbrella
        // configure_partner_registration does everything (spec conformance).
        when(networkService.getRegistration("client-1", "tenant-ext-1"))
                .thenReturn(process(OnboardingState.COMPLETED));
        when(networkService.cancelRegistration("client-1", "tenant-ext-1"))
                .thenReturn(process(OnboardingState.CANCELLED));

        // registration:write — POST and DELETE pass, GET is 403
        mockMvc.perform(post(TENANT_PATH).with(clientWithScope(ApiSecurityConfig.REGISTRATION_WRITE))
                        .contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isCreated());
        mockMvc.perform(delete(TENANT_PATH + "/tenant-ext-1").with(clientWithScope(ApiSecurityConfig.REGISTRATION_WRITE)))
                .andExpect(status().isNoContent());
        mockMvc.perform(get(TENANT_PATH + "/tenant-ext-1").with(clientWithScope(ApiSecurityConfig.REGISTRATION_WRITE)))
                .andExpect(status().isForbidden());

        // registration:read — GETs pass, POST and DELETE are 403
        mockMvc.perform(get(TENANT_PATH + "/tenant-ext-1").with(clientWithScope(ApiSecurityConfig.REGISTRATION_READ)))
                .andExpect(status().isOk());
        mockMvc.perform(get(TENANT_PATH).with(clientWithScope(ApiSecurityConfig.REGISTRATION_READ)))
                .andExpect(status().isOk());
        mockMvc.perform(post(TENANT_PATH).with(clientWithScope(ApiSecurityConfig.REGISTRATION_READ))
                        .contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isForbidden());
        mockMvc.perform(delete(TENANT_PATH + "/tenant-ext-1").with(clientWithScope(ApiSecurityConfig.REGISTRATION_READ)))
                .andExpect(status().isForbidden());

        // a callback-config scope grants nothing here
        mockMvc.perform(get(TENANT_PATH + "/tenant-ext-1").with(clientWithScope(ApiSecurityConfig.CALLBACK_CONFIG_WRITE)))
                .andExpect(status().isForbidden());
    }

    private static ObjectNode validPayload() throws Exception {
        return (ObjectNode) MAPPER.readTree(VALID_BODY);
    }
}
