package com.metaform.cxve.adapter.in.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.metaform.cxve.application.NetworkService;
import com.metaform.cxve.config.ApiSecurityConfig;
import com.metaform.cxve.domain.model.CompanyRoleId;
import com.metaform.cxve.domain.model.ConsentStatusId;
import com.metaform.cxve.domain.model.FileUploadResponse;
import com.metaform.cxve.domain.model.PartnerRegistrationData;
import com.metaform.cxve.domain.model.UniqueIdentifierId;
import java.time.Instant;
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
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(NetworkController.class)
@Import(ApiSecurityConfig.class)
@ExtendWith(OutputCaptureExtension.class)
class NetworkControllerTest {

    /** Required fields that are lists (@NotEmpty); the rest are strings (@NotBlank). */
    private static final Set<String> LIST_FIELDS = Set.of("uniqueIds", "companyRoles", "agreements");

    /** A complete payload carrying every required field: name, shortName, uniqueIds, externalId, companyRoles, agreements. */
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
              "agreements": [
                { "agreementId": "Catena-X", "consentStatus": "ACTIVE" }
              ],
              "fileIds": [ "file-1" ],
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

    @Test
    void registerPartner_withoutABearerToken_is401() throws Exception {
        // Registration needs no particular scope, but it does need an authenticated caller.
        mockMvc.perform(post("/api/administration/registration/network/partnerregistration")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(networkService);
    }

    @Test
    void registerPartner_returns200AndDelegatesToService() throws Exception {
        mockMvc.perform(post("/api/administration/registration/network/partnerregistration")
                        .with(jwt().jwt(j -> j.subject("client-1")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isOk());

        var captor = ArgumentCaptor.forClass(PartnerRegistrationData.class);
        verify(networkService).registerPartner(eq("client-1"), captor.capture());

        var data = captor.getValue();
        assertThat(data.name()).isEqualTo("Acme Corp");
        assertThat(data.countryAlpha2Code()).isEqualTo("DE");
        assertThat(data.bpn()).isEqualTo("BPNL000000000001");
        assertThat(data.shortName()).isEqualTo("Acme");
        assertThat(data.externalId()).isEqualTo("ext-123");
        assertThat(data.companyRoles()).containsExactly(CompanyRoleId.ACTIVE_PARTICIPANT, CompanyRoleId.ONBOARDING_SERVICE_PROVIDER);
        assertThat(data.uniqueIds()).hasSize(1);
        assertThat(data.uniqueIds().get(0).type()).isEqualTo(UniqueIdentifierId.VAT_ID);
        assertThat(data.uniqueIds().get(0).value()).isEqualTo("DE123456789");
        assertThat(data.userDetails()).hasSize(1);
        assertThat(data.userDetails().get(0).email()).isEqualTo("john.doe@acme.example");
        assertThat(data.agreements()).hasSize(1);
        assertThat(data.agreements().get(0).agreementId()).isEqualTo("Catena-X");
        assertThat(data.agreements().get(0).consentStatus()).isEqualTo(ConsentStatusId.ACTIVE);
        assertThat(data.fileIds()).containsExactly("file-1");
        assertThat(data.autoSubmit()).isTrue();
    }

    @Test
    void registerPartner_withMalformedJson_returns400WithMessageAndLogs(CapturedOutput output) throws Exception {
        mockMvc.perform(post("/api/administration/registration/network/partnerregistration")
                        .with(jwt().jwt(j -> j.subject("client-1")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{ not json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").isNotEmpty());

        assertThat(output).contains("Rejected request with 400 due to invalid shape:");
    }

    @Test
    void registerPartner_withUnknownEnumValue_returns400WithMessageAndLogs(CapturedOutput output) throws Exception {
        mockMvc.perform(post("/api/administration/registration/network/partnerregistration")
                        .with(jwt().jwt(j -> j.subject("client-1")))
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

        mockMvc.perform(post("/api/administration/registration/network/partnerregistration")
                        .with(jwt().jwt(j -> j.subject("client-1")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload.toString()))
                .andExpect(status().isOk());

        var captor = ArgumentCaptor.forClass(PartnerRegistrationData.class);
        verify(networkService).registerPartner(eq("client-1"), captor.capture());
        assertThat(captor.getValue().bpn()).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = { "name", "shortName", "uniqueIds", "externalId", "companyRoles", "agreements" })
    void registerPartner_withMissingRequiredField_returns400WithMessageAndLogs(String field, CapturedOutput output) throws Exception {
        var payload = validPayload();
        payload.remove(field);
        var expectedMessage = field + ": " + (LIST_FIELDS.contains(field) ? "must not be empty" : "must not be blank");

        mockMvc.perform(post("/api/administration/registration/network/partnerregistration")
                        .with(jwt().jwt(j -> j.subject("client-1")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload.toString()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(expectedMessage));

        assertThat(output).contains("Rejected request with 400 due to invalid shape: " + expectedMessage);
        verifyNoInteractions(networkService);
    }

    @ParameterizedTest
    @ValueSource(strings = { "name", "shortName", "externalId" })
    void registerPartner_withBlankRequiredField_returns400WithMessageAndLogs(String field, CapturedOutput output) throws Exception {
        var payload = validPayload();
        payload.put(field, "   ");
        var expectedMessage = field + ": must not be blank";

        mockMvc.perform(post("/api/administration/registration/network/partnerregistration")
                        .with(jwt().jwt(j -> j.subject("client-1")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload.toString()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(expectedMessage));

        assertThat(output).contains("Rejected request with 400 due to invalid shape: " + expectedMessage);
        verifyNoInteractions(networkService);
    }

    @ParameterizedTest
    @ValueSource(strings = { "uniqueIds", "companyRoles", "agreements" })
    void registerPartner_withEmptyRequiredList_returns400WithMessageAndLogs(String field, CapturedOutput output) throws Exception {
        var payload = validPayload();
        payload.putArray(field);
        var expectedMessage = field + ": must not be empty";

        mockMvc.perform(post("/api/administration/registration/network/partnerregistration")
                        .with(jwt().jwt(j -> j.subject("client-1")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload.toString()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(expectedMessage));

        assertThat(output).contains("Rejected request with 400 due to invalid shape: " + expectedMessage);
        verifyNoInteractions(networkService);
    }

    @Test
    void uploadFile_withoutABearerToken_is401() throws Exception {
        mockMvc.perform(post("/api/administration/registration/network/partnerregistration/fileupload")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "fileName": "extract.pdf", "contentType": "application/pdf" }
                                """))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(networkService);
    }

    @Test
    void uploadFile_returnsTheUploadTarget() throws Exception {
        when(networkService.initiateFileUpload("extract.pdf", "application/pdf"))
                .thenReturn(new FileUploadResponse("file-1", "https://storage.example/upload/file-1",
                        Instant.parse("2026-08-25T12:00:00Z")));

        mockMvc.perform(post("/api/administration/registration/network/partnerregistration/fileupload")
                        .with(jwt().jwt(j -> j.subject("client-1")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "fileName": "extract.pdf", "contentType": "application/pdf" }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fileId").value("file-1"))
                .andExpect(jsonPath("$.presignedUploadUrl").value("https://storage.example/upload/file-1"))
                .andExpect(jsonPath("$.expiresAt").value("2026-08-25T12:00:00Z"));
    }

    @ParameterizedTest
    @ValueSource(strings = { "fileName", "contentType" })
    void uploadFile_withMissingRequiredField_returns400WithMessageAndLogs(String field, CapturedOutput output) throws Exception {
        var payload = (ObjectNode) MAPPER.readTree("""
                { "fileName": "extract.pdf", "contentType": "application/pdf" }
                """);
        payload.remove(field);
        var expectedMessage = field + ": must not be blank";

        mockMvc.perform(post("/api/administration/registration/network/partnerregistration/fileupload")
                        .with(jwt().jwt(j -> j.subject("client-1")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload.toString()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(expectedMessage));

        assertThat(output).contains("Rejected request with 400 due to invalid shape: " + expectedMessage);
        verifyNoInteractions(networkService);
    }

    private static ObjectNode validPayload() throws Exception {
        return (ObjectNode) MAPPER.readTree(VALID_BODY);
    }
}
