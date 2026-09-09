package com.metaform.cxve.adapter.in.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.metaform.cxve.application.NetworkService;
import com.metaform.cxve.config.ApiSecurityConfig;
import com.metaform.cxve.domain.model.CompanyRoleId;
import com.metaform.cxve.domain.model.PartnerRegistrationData;
import com.metaform.cxve.domain.model.UniqueIdentifierId;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
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
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(NetworkController.class)
@Import(ApiSecurityConfig.class)
@ExtendWith(OutputCaptureExtension.class)
class NetworkControllerTest {

    private static final String REGISTRATION_PATH = "/api/administration/registration/network/partnerregistration";

    /** Required fields that are lists (@NotEmpty); the rest are strings (@NotBlank). */
    private static final Set<String> LIST_FIELDS = Set.of("uniqueIds", "companyRoles", "userDetails");

    /**
     * A complete payload carrying every spec-mandatory field — externalId, name, city, streetName,
     * countryAlpha2Code, region, companyRoles, uniqueIds, userDetails — plus the optional ones.
     */
    private static final String VALID_BODY = """
            {
              "name": "Acme Corp",
              "city": "Berlin",
              "streetName": "Musterstrasse",
              "countryAlpha2Code": "DE",
              "bpn": "BPNL000000000001",
              "shortName": "Acme",
              "region": "BE",
              "externalId": "ext-123",
              "uniqueIds": [
                { "type": "VAT_ID", "value": "DE123456789" }
              ],
              "userDetails": [
                {
                  "providerId": "prov-1",
                  "username": "jdoe",
                  "firstName": "John",
                  "lastName": "Doe",
                  "email": "john.doe@acme.example"
                }
              ],
              "companyRoles": [ "ACTIVE_PARTICIPANT", "ONBOARDING_SERVICE_PROVIDER" ],
              "autoSubmit": true
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

    /** An OSP caller carrying the configure_partner_registration scope every endpoint requires. */
    private static JwtRequestPostProcessor ospClient() {
        return jwt().jwt(j -> j.subject("client-1"))
                .authorities(new SimpleGrantedAuthority(ApiSecurityConfig.CONFIGURE_PARTNER_REGISTRATION));
    }

    @Test
    void registerPartner_withoutABearerToken_is401() throws Exception {
        mockMvc.perform(post(REGISTRATION_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(networkService);
    }

    @Test
    void registerPartner_withoutTheConfigureScope_is403() throws Exception {
        // CX-0009 declares the configure_partner_registration role on EVERY CSP-B endpoint; an
        // authenticated token without the scope must not reach the service.
        mockMvc.perform(post(REGISTRATION_PATH)
                        .with(jwt().jwt(j -> j.subject("client-1")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isForbidden());

        verifyNoInteractions(networkService);
    }

    @Test
    void registerPartner_returns200AndDelegatesToService() throws Exception {
        mockMvc.perform(post(REGISTRATION_PATH)
                        .with(ospClient())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isOk());

        var captor = ArgumentCaptor.forClass(PartnerRegistrationData.class);
        verify(networkService).registerPartner(eq("client-1"), captor.capture());

        var data = captor.getValue();
        assertThat(data.name()).isEqualTo("Acme Corp");
        assertThat(data.city()).isEqualTo("Berlin");
        assertThat(data.streetName()).isEqualTo("Musterstrasse");
        assertThat(data.countryAlpha2Code()).isEqualTo("DE");
        assertThat(data.region()).isEqualTo("BE");
        assertThat(data.bpn()).isEqualTo("BPNL000000000001");
        assertThat(data.shortName()).isEqualTo("Acme");
        assertThat(data.externalId()).isEqualTo("ext-123");
        assertThat(data.companyRoles()).containsExactly(CompanyRoleId.ACTIVE_PARTICIPANT, CompanyRoleId.ONBOARDING_SERVICE_PROVIDER);
        assertThat(data.uniqueIds()).hasSize(1);
        assertThat(data.uniqueIds().get(0).type()).isEqualTo(UniqueIdentifierId.VAT_ID);
        assertThat(data.uniqueIds().get(0).value()).isEqualTo("DE123456789");
        assertThat(data.userDetails()).hasSize(1);
        assertThat(data.userDetails().get(0).email()).isEqualTo("john.doe@acme.example");
        assertThat(data.autoSubmit()).isTrue();
    }

    @Test
    void registerPartner_withMalformedJson_returns400WithMessageAndLogs(CapturedOutput output) throws Exception {
        mockMvc.perform(post(REGISTRATION_PATH)
                        .with(ospClient())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{ not json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").isNotEmpty());

        assertThat(output).contains("Rejected request with 400 due to invalid shape:");
    }

    @Test
    void registerPartner_withUnknownEnumValue_returns400WithMessageAndLogs(CapturedOutput output) throws Exception {
        mockMvc.perform(post(REGISTRATION_PATH)
                        .with(ospClient())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "companyRoles": [ "NOT_A_ROLE" ] }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").isNotEmpty());

        assertThat(output).contains("Rejected request with 400 due to invalid shape:");
    }

    @Test
    void registerPartner_withoutBpn_isAccepted() throws Exception {
        // The BPN is deliberately optional on ingress: when absent, the BusinessPartnerNumberService
        // assigns one at the BPN step of the onboarding.
        var payload = validPayload();
        payload.remove("bpn");

        mockMvc.perform(post(REGISTRATION_PATH)
                        .with(ospClient())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload.toString()))
                .andExpect(status().isOk());

        var captor = ArgumentCaptor.forClass(PartnerRegistrationData.class);
        verify(networkService).registerPartner(eq("client-1"), captor.capture());
        assertThat(captor.getValue().bpn()).isNull();
    }

    @Test
    void registerPartner_withoutShortName_isAccepted() throws Exception {
        // shortName is Optional per spec; the DID resolver falls back to the externalId.
        var payload = validPayload();
        payload.remove("shortName");

        mockMvc.perform(post(REGISTRATION_PATH)
                        .with(ospClient())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload.toString()))
                .andExpect(status().isOk());

        var captor = ArgumentCaptor.forClass(PartnerRegistrationData.class);
        verify(networkService).registerPartner(eq("client-1"), captor.capture());
        assertThat(captor.getValue().shortName()).isNull();
    }

    @Test
    void registerPartner_toleratesUnknownProperties() throws Exception {
        // Tolerant reader per the normative schema (additionalProperties: true) — extras are
        // ignored, notably the agreements/fileIds fields of earlier spec revisions.
        var payload = validPayload();
        payload.putArray("agreements").addObject().put("agreementId", "Catena-X").put("consentStatus", "ACTIVE");
        payload.putArray("fileIds").add("file-1");
        payload.put("someFutureProperty", "value");

        mockMvc.perform(post(REGISTRATION_PATH)
                        .with(ospClient())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload.toString()))
                .andExpect(status().isOk());

        verify(networkService).registerPartner(eq("client-1"), org.mockito.ArgumentMatchers.any());
    }

    @ParameterizedTest
    @ValueSource(strings = { "externalId", "name", "city", "streetName", "countryAlpha2Code", "region",
            "uniqueIds", "companyRoles", "userDetails" })
    void registerPartner_withMissingRequiredField_returns400WithMessageAndLogs(String field, CapturedOutput output) throws Exception {
        var payload = validPayload();
        payload.remove(field);
        var expectedMessage = field + ": " + (LIST_FIELDS.contains(field) ? "must not be empty" : "must not be blank");

        mockMvc.perform(post(REGISTRATION_PATH)
                        .with(ospClient())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload.toString()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(expectedMessage));

        assertThat(output).contains("Rejected request with 400 due to invalid shape: " + expectedMessage);
        verifyNoInteractions(networkService);
    }

    @ParameterizedTest
    @ValueSource(strings = { "externalId", "name", "city", "streetName", "countryAlpha2Code", "region" })
    void registerPartner_withBlankRequiredField_returns400WithMessageAndLogs(String field, CapturedOutput output) throws Exception {
        var payload = validPayload();
        payload.put(field, "   ");
        var expectedMessage = field + ": must not be blank";

        mockMvc.perform(post(REGISTRATION_PATH)
                        .with(ospClient())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload.toString()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(expectedMessage));

        assertThat(output).contains("Rejected request with 400 due to invalid shape: " + expectedMessage);
        verifyNoInteractions(networkService);
    }

    @ParameterizedTest
    @ValueSource(strings = { "uniqueIds", "companyRoles", "userDetails" })
    void registerPartner_withEmptyRequiredList_returns400WithMessageAndLogs(String field, CapturedOutput output) throws Exception {
        var payload = validPayload();
        payload.putArray(field);
        var expectedMessage = field + ": must not be empty";

        mockMvc.perform(post(REGISTRATION_PATH)
                        .with(ospClient())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload.toString()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(expectedMessage));

        assertThat(output).contains("Rejected request with 400 due to invalid shape: " + expectedMessage);
        verifyNoInteractions(networkService);
    }

    @ParameterizedTest
    @ValueSource(strings = { "providerId", "firstName", "lastName", "email" })
    void registerPartner_withIncompleteUserDetail_returns400(String field) throws Exception {
        // The spec marks these UserDetailData fields Mandatory.
        var payload = validPayload();
        ((ObjectNode) payload.withArray("userDetails").get(0)).remove(field);

        mockMvc.perform(post(REGISTRATION_PATH)
                        .with(ospClient())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload.toString()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").isNotEmpty());

        verifyNoInteractions(networkService);
    }

    @Test
    void fileUploadEndpoint_isGone() throws Exception {
        // The former fileupload endpoint was removed with the spec revision that relocated it;
        // this deployment omits the (per §2.2.3 optional) file upload entirely.
        mockMvc.perform(post(REGISTRATION_PATH + "/fileupload")
                        .with(ospClient())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "fileName": "extract.pdf", "externalId": "ext-123", "contentType": "application/pdf" }
                                """))
                .andExpect(status().isNotFound());

        verifyNoInteractions(networkService);
    }

    private static ObjectNode validPayload() throws Exception {
        return (ObjectNode) MAPPER.readTree(VALID_BODY);
    }
}
