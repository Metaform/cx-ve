package com.metaform.dxonboarding.adapter.in.web;

import com.metaform.dxonboarding.application.OnboardingRequestService;
import com.metaform.dxonboarding.config.SecurityConfig;
import com.metaform.dxonboarding.domain.InvalidOnboardingRequestException;
import com.metaform.dxonboarding.domain.model.onboarding.CompanyType;
import com.metaform.dxonboarding.domain.model.onboarding.DocumentRef;
import com.metaform.dxonboarding.domain.model.onboarding.OnboardingRequest;
import com.metaform.dxonboarding.domain.model.onboarding.OnboardingStatus;
import com.metaform.dxonboarding.domain.model.onboarding.Submission;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The intake's wire format: a multipart submission, a status read, both scoped to the stamped connector. */
@WebMvcTest(controllers = OnboardingRequestController.class)
@Import(SecurityConfig.class)
class OnboardingRequestControllerTest {

    private static final String PATH = "/api/v1/onboarding-requests";
    private static final Instant SUBMITTED_AT = Instant.parse("2026-10-02T10:00:00Z");

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private OnboardingRequestService service;

    /** The submission of the Decade-X sample call: the request, the GTC and two UCAs. */
    private static MockMultipartHttpServletRequestBuilder sampleSubmission() throws IOException {
        var json = new ClassPathResource("onboarding-request.json").getContentAsString(StandardCharsets.UTF_8);
        return multipart(PATH)
                .file(new MockMultipartFile("request", null, MediaType.APPLICATION_JSON_VALUE, json.getBytes()))
                .file(new MockMultipartFile("gtcDocument", "gtc.pdf", "application/pdf", "%PDF gtc".getBytes()))
                .file(new MockMultipartFile("ucaDocument[export-control]", "uca-export-control.pdf", "application/pdf",
                        "%PDF uca 1".getBytes()))
                .file(new MockMultipartFile("ucaDocument[critical-supply-chain]", "uca-critical-supply-chain.pdf",
                        "application/pdf", "%PDF uca 2".getBytes()));
    }

    private static OnboardingRequest filed(String id) {
        return new OnboardingRequest(id, "DX-OR-000001", "connector-a", SUBMITTED_AT, OnboardingStatus.SUBMITTED,
                null, null, null, null, null, null, Map.of(), null);
    }

    @Test
    void submit_handsTheRequestAndItsDocumentsOverForTheStampedConnector() throws Exception {
        var id = UUID.randomUUID().toString();
        when(service.submit(eq("connector-a"), any())).thenReturn(filed(id));

        mvc.perform(sampleSubmission().header("X-Connector-Id", "connector-a").with(jwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.businessId").value("DX-OR-000001"))
                .andExpect(jsonPath("$.status").value("SUBMITTED"))
                .andExpect(jsonPath("$.connectorId").value("connector-a"))
                .andExpect(jsonPath("$.submittedAt").value("2026-10-02T10:00:00Z"));

        var submission = ArgumentCaptor.forClass(Submission.class);
        verify(service).submit(eq("connector-a"), submission.capture());
        var request = submission.getValue().request();
        assertThat(request.legalEntity().legalName()).isEqualTo("Example Aerospace GmbH");
        assertThat(request.legalEntity().companyType()).isEqualTo(CompanyType.LIMITED_LIABILITY_COMPANY);
        assertThat(request.ucas()).hasSize(2);
        assertThat(submission.getValue().documents()).extracting(document -> document.part()).containsExactlyInAnyOrder(
                "gtcDocument", "ucaDocument[export-control]", "ucaDocument[critical-supply-chain]");
        assertThat(submission.getValue().violations()).isEmpty();
    }

    @Test
    void submit_answersAnIncompleteRequestWith422AndItsViolations() throws Exception {
        when(service.submit(eq("connector-a"), any()))
                .thenThrow(new InvalidOnboardingRequestException(List.of("gtcDocument: required")));

        mvc.perform(multipart(PATH).header("X-Connector-Id", "connector-a").with(jwt()))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0]").value("gtcDocument: required"));
    }

    @Test
    void submit_requiresTheConnectorIdentity() throws Exception {
        mvc.perform(sampleSubmission().with(jwt()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0]").value("No connector identity: the 'X-Connector-Id' header is missing"));
        mvc.perform(get(PATH + "/some-id").header("X-Connector-Id", " ").with(jwt()))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(service);
    }

    @Test
    void submit_requiresABearer() throws Exception {
        mvc.perform(sampleSubmission().header("X-Connector-Id", "connector-a")).andExpect(status().isUnauthorized());

        verifyNoInteractions(service);
    }

    @Test
    void get_returnsTheRequestWithItsStatus() throws Exception {
        var id = UUID.randomUUID().toString();
        var gtc = new DocumentRef(UUID.randomUUID(), "gtc.pdf", "application/pdf", 8, SUBMITTED_AT);
        when(service.submit(eq("connector-a"), any())).thenReturn(filed(id));
        // file the sample for real, through the domain, to read back what the view makes of it
        mvc.perform(sampleSubmission().header("X-Connector-Id", "connector-a").with(jwt()));
        var captor = ArgumentCaptor.forClass(Submission.class);
        verify(service).submit(eq("connector-a"), captor.capture());
        var data = captor.getValue().request();
        when(service.get("connector-a", id)).thenReturn(new OnboardingRequest(id, "DX-OR-000001", "connector-a",
                SUBMITTED_AT, OnboardingStatus.UNDER_REVIEW, data, null, null, gtc, null, null, Map.of(), null));

        mvc.perform(get(PATH + "/" + id).header("X-Connector-Id", "connector-a").with(jwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UNDER_REVIEW"))
                .andExpect(jsonPath("$.legalEntity.legalName").value("Example Aerospace GmbH"))
                .andExpect(jsonPath("$.legalEntity.registrationNumbers[0].scheme").value("vatID"))
                .andExpect(jsonPath("$.businessSites[0].siteName").value("Headquarters"))
                .andExpect(jsonPath("$.gtc.versionNumber").value("1.0"))
                .andExpect(jsonPath("$.gtc.acceptedAt").value("2026-10-02T10:00:00Z"))
                .andExpect(jsonPath("$.gtc.document.filename").value("gtc.pdf"))
                .andExpect(jsonPath("$.ucas[1].useCaseId").value("critical-supply-chain"))
                .andExpect(jsonPath("$.declarations.authorisedToAct").value(true))
                .andExpect(jsonPath("$.decision").doesNotExist());
    }

    @Test
    void get_answersAnUnknownOrForeignRequestWith404() throws Exception {
        when(service.get("connector-b", "some-id")).thenThrow(new NoSuchElementException("No onboarding request some-id"));

        mvc.perform(get(PATH + "/some-id").header("X-Connector-Id", "connector-b").with(jwt()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errors[0]").value("No onboarding request some-id"));
    }
}
