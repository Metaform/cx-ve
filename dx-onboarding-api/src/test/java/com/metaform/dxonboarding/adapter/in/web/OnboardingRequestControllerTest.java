package com.metaform.dxonboarding.adapter.in.web;

import com.jayway.jsonpath.JsonPath;
import com.metaform.dxonboarding.adapter.out.document.InMemoryDocumentStore;
import com.metaform.dxonboarding.adapter.out.persistence.InMemoryOnboardingRequestRepository;
import com.metaform.dxonboarding.application.AutomaticReview;
import com.metaform.dxonboarding.application.DefaultOnboardingRequestService;
import com.metaform.dxonboarding.config.ReviewConfig;
import com.metaform.dxonboarding.config.SecurityConfig;
import com.metaform.dxonboarding.domain.port.DidResolver;
import com.metaform.dxonboarding.domain.port.OnboardingEventPublisher;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.AbstractMockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The intake's wire format, against the real service: the draft → documents → submit flow, the
 * versions in {@code ETag}/{@code If-Match}, and every problem the API answers with. Each test
 * starts from an empty store: the draft quota and the DID uniqueness would otherwise carry over.
 */
@WebMvcTest(controllers = OnboardingRequestController.class)
@Import({SecurityConfig.class, ReviewConfig.class, UnknownProperties.class, DefaultOnboardingRequestService.class,
        InMemoryOnboardingRequestRepository.class, InMemoryDocumentStore.class})
@ExtendWith(OutputCaptureExtension.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class OnboardingRequestControllerTest {

    private static final String PATH = "/api/v1/onboarding-requests";
    private static final String DID = "did:web:example-aerospace.example.org";
    private static final String CONNECTOR = DID;
    private static final byte[] PDF = "%PDF-1.4 signed".getBytes(StandardCharsets.US_ASCII);

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private AutomaticReview review;

    @MockitoBean
    private OnboardingEventPublisher events;

    /** Every DID resolves, unless a test says otherwise. */
    @MockitoBean
    private DidResolver didResolver;

    private static String sampleRequest() throws Exception {
        return new ClassPathResource("onboarding-request.json").getContentAsString(StandardCharsets.UTF_8);
    }

    /** A request a submit needs only the GTC for. */
    private static String minimalRequest(String did, String reference) {
        return """
                {"legalEntity": {"legalName": "Acme Corp", "preferredDid": "%s", "registrationCountry": "DE",
                                 "companyType": "LIMITED_LIABILITY_COMPANY",
                                 "registrationNumbers": [{"scheme": "vatID", "value": "DE123456789"}],
                                 "legalAddress": {"street": "Beispielstrasse 1", "locality": "Berlin", "countryCode": "DE"}},
                 "legalPerson": {"fullName": "Erika Mustermann", "email": "erika@example.org"},
                 "gtc": {"versionNumber": "1.0"},
                 "declarations": {"informationAccurate": true, "authorisedToAct": true, "evidenceMayBeRequested": true},
                 "applicantReference": "%s"}""".formatted(did, reference);
    }

    private static <B extends AbstractMockHttpServletRequestBuilder<B>> B as(B request, String connectorId) {
        return request.header("X-Connector-Id", connectorId).with(jwt());
    }

    private ResultActions create(String connectorId, String body) throws Exception {
        return mvc.perform(as(post(PATH), connectorId).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    /** Creates the draft; returns its id. */
    private String created(String connectorId, String body) throws Exception {
        return JsonPath.read(create(connectorId, body).andExpect(status().isCreated()).andReturn()
                .getResponse().getContentAsString(), "$.id");
    }

    private MockMultipartHttpServletRequestBuilder documentUpload(String requestId, String version, String type,
                                                                  String useCaseId, String label, MockMultipartFile file) {
        var request = as(multipart(PATH + "/{id}/documents", requestId), CONNECTOR);
        if (file != null) {
            request.file(file);
        }
        request.header(HttpHeaders.IF_MATCH, version);
        if (type != null) {
            request.param("type", type);
        }
        if (useCaseId != null) {
            request.param("useCaseId", useCaseId);
        }
        if (label != null) {
            request.param("label", label);
        }
        return request;
    }

    private static MockMultipartFile pdf(String filename) {
        return new MockMultipartFile("file", filename, "application/pdf", PDF);
    }

    private ResultActions addDocument(String requestId, String version, String type, String useCaseId, String label,
                                      MockMultipartFile file) throws Exception {
        return mvc.perform(documentUpload(requestId, version, type, useCaseId, label, file));
    }

    /** The request's current ETag. */
    private String etag(String connectorId, String requestId) throws Exception {
        return mvc.perform(as(get(PATH + "/{id}", requestId), connectorId)).andReturn().getResponse()
                .getHeader(HttpHeaders.ETAG);
    }

    /** The sample request with all its documents: version "4". */
    private String completeSample() throws Exception {
        var id = created(CONNECTOR, sampleRequest());
        addDocument(id, "\"1\"", "gtc", null, null, pdf("gtc.pdf")).andExpect(status().isCreated());
        addDocument(id, "\"2\"", "uca", "export-control", null, pdf("uca-1.pdf")).andExpect(status().isCreated());
        addDocument(id, "\"3\"", "uca", "product-conformity", null, pdf("uca-2.pdf")).andExpect(status().isCreated());
        return id;
    }

    private ResultActions submit(String connectorId, String requestId, String version) throws Exception {
        return mvc.perform(as(post(PATH + "/{id}/submit", requestId), connectorId).header(HttpHeaders.IF_MATCH, version));
    }

    private static ResultActions problem(ResultActions result, int status, String code) throws Exception {
        return result.andExpect(status().is(status))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("urn:decade-x:tsp:problem:" + code))
                .andExpect(jsonPath("$.status").value(status))
                .andExpect(jsonPath("$.title").isNotEmpty())
                .andExpect(jsonPath("$.detail").isNotEmpty());
    }

    @Test
    void create_makesADraft_withItsVersionInTheBodyAndTheETag() throws Exception {
        create(CONNECTOR, sampleRequest())
                .andExpect(status().isCreated())
                .andExpect(header().string(HttpHeaders.ETAG, "\"1\""))
                .andExpect(jsonPath("$.id").isNotEmpty())
                .andExpect(jsonPath("$.version").value("1"))
                .andExpect(jsonPath("$.businessId").value("DX-OR-000001"))
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.connectorId").value(CONNECTOR))
                .andExpect(jsonPath("$.submittedAt").doesNotExist())
                .andExpect(jsonPath("$.legalEntity.preferredDid").value(DID))
                .andExpect(jsonPath("$.legalEntity.legalEntityId").doesNotExist())
                .andExpect(jsonPath("$.businessSites[0].siteName").value("Headquarters"))
                .andExpect(jsonPath("$.gtc.versionNumber").value("1.0"))
                .andExpect(jsonPath("$.gtc.versionId").doesNotExist())
                .andExpect(jsonPath("$.gtc.accepted").doesNotExist())
                .andExpect(jsonPath("$.ucas[1].useCaseId").value("product-conformity"))
                .andExpect(jsonPath("$.ucas[1].useCaseName").value("Product Conformity"))
                .andExpect(jsonPath("$.ucas[1].document").doesNotExist())
                .andExpect(jsonPath("$.otherDocuments").isEmpty())
                .andExpect(jsonPath("$.decision").doesNotExist());
    }

    @Test
    void create_sentAgain_returnsTheFirstDraft_with200() throws Exception {
        var id = created(CONNECTOR, sampleRequest());

        create(CONNECTOR, sampleRequest()).andExpect(status().isOk()).andExpect(jsonPath("$.id").value(id));
        // the spec's "/" is served with and without the trailing slash
        mvc.perform(as(post(PATH + "/"), CONNECTOR).contentType(MediaType.APPLICATION_JSON).content(sampleRequest()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(id));
    }

    @Test
    void create_withAKeyUsedForOtherContent_isRefused() throws Exception {
        mvc.perform(as(post(PATH), CONNECTOR).header("Idempotency-Key", "key-1").contentType(MediaType.APPLICATION_JSON)
                .content(sampleRequest())).andExpect(status().isCreated());

        problem(mvc.perform(as(post(PATH), CONNECTOR).header("Idempotency-Key", "key-1")
                .contentType(MediaType.APPLICATION_JSON).content(minimalRequest(DID, "other"))), 409, "idempotency-key-reused");
    }

    @Test
    void create_withAnInvalidBody_listsEveryProblem() throws Exception {
        problem(create(CONNECTOR, sampleRequest().replace("\"export-control\"", "\"ccm\"")), 400, "invalid-request")
                .andExpect(jsonPath("$.errors[0].field").value("ucas[0].useCaseId"))
                .andExpect(jsonPath("$.errors[0].message").value(org.hamcrest.Matchers.containsString("product-conformity")));
        problem(create(CONNECTOR, sampleRequest().replace("\"DE\",\n    \"companyType\"", "\"DEU\",\n    \"companyType\"")
                .replace("\"1.0\"", "\"" + "1".repeat(33) + "\"")), 400, "invalid-request")
                .andExpect(jsonPath("$.errors[*].field").value(org.hamcrest.Matchers.contains(
                        "legalEntity.registrationCountry", "gtc.versionNumber")));
        problem(create(CONNECTOR, "{\"ucas\": [{\"useCaseId\": \"engineering\"}, {\"useCaseId\": \"engineering\"}]}"),
                400, "invalid-request").andExpect(jsonPath("$.errors[0].field").value("ucas[1].useCaseId"));
        problem(create(CONNECTOR, "not json"), 400, "invalid-request");
    }

    @Test
    void create_ignoresUnknownProperties_withAWarning(CapturedOutput output) throws Exception {
        var body = sampleRequest().replaceFirst("\\{", "{\"channel\": \"web\", ")
                .replace("\"legalName\": \"Example Aerospace GmbH\"", "\"legalName\": \"Example Aerospace GmbH\", \"legalEntityId\": \"DX-12345678\", \"lei\": {\"code\": 1}")
                .replace("\"useCaseId\": \"product-conformity\"", "\"useCaseId\": \"product-conformity\", \"verified\": true");

        create(CONNECTOR, body).andExpect(status().isCreated())
                .andExpect(jsonPath("$.legalEntity.legalEntityId").doesNotExist());

        assertThat(output).contains("unknown properties ignored: [legalEntity.lei, ucas[1].verified, channel]");
        // the deprecated useCaseName is dropped silently; a DECADE-X-ID from outside the VE is ignored, and said so
        assertThat(output).doesNotContain("useCaseName").contains("legalEntity.legalEntityId 'DX-12345678' ignored");
    }

    @Test
    void everyCall_needsTheConnectorIdentity() throws Exception {
        problem(mvc.perform(post(PATH).with(jwt()).contentType(MediaType.APPLICATION_JSON).content(sampleRequest())),
                401, "unauthorized");
        problem(mvc.perform(get(PATH + "/{id}", "x").with(jwt())), 401, "unauthorized");
    }

    @Test
    void get_returnsTheRequest_toItsConnectorOnly() throws Exception {
        var id = created(CONNECTOR, sampleRequest());

        mvc.perform(as(get(PATH + "/{id}", id), CONNECTOR))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ETAG, "\"1\""))
                .andExpect(jsonPath("$.id").value(id));
        problem(mvc.perform(as(get(PATH + "/{id}", id), "did:web:other.example.com")), 404, "not-found");
    }

    @Test
    void aChange_needsTheCurrentVersionInIfMatch() throws Exception {
        var id = created(CONNECTOR, sampleRequest());
        var body = minimalRequest(DID, "ext-0001");

        problem(mvc.perform(as(put(PATH + "/{id}", id), CONNECTOR).contentType(MediaType.APPLICATION_JSON).content(body)),
                428, "precondition-required");
        for (var malformed : new String[]{"W/\"1\"", "*", "\"1\", \"2\"", "1", "\"one\""}) {
            problem(mvc.perform(as(put(PATH + "/{id}", id), CONNECTOR).header(HttpHeaders.IF_MATCH, malformed)
                    .contentType(MediaType.APPLICATION_JSON).content(body)), 400, "malformed-if-match");
        }
        problem(mvc.perform(as(put(PATH + "/{id}", id), CONNECTOR).header(HttpHeaders.IF_MATCH, "\"0\"")
                .contentType(MediaType.APPLICATION_JSON).content(body)), 409, "version-conflict");

        mvc.perform(as(put(PATH + "/{id}", id), CONNECTOR).header(HttpHeaders.IF_MATCH, "\"1\"")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ETAG, "\"2\""))
                .andExpect(jsonPath("$.ucas").isEmpty());
    }

    @Test
    void documents_areAdded_listed_downloaded_replaced_andRemoved() throws Exception {
        var id = created(CONNECTOR, sampleRequest());

        var added = addDocument(id, "\"1\"", "gtc", null, null, pdf("gtc.pdf"))
                .andExpect(status().isCreated())
                .andExpect(header().string(HttpHeaders.ETAG, "\"2\""))
                .andExpect(jsonPath("$.version").value("2"))
                .andExpect(jsonPath("$.document.type").value("gtc"))
                .andExpect(jsonPath("$.document.filename").value("gtc.pdf"))
                .andExpect(jsonPath("$.document.contentType").value("application/pdf"))
                .andExpect(jsonPath("$.document.sizeBytes").value(PDF.length))
                .andExpect(jsonPath("$.document.useCaseId").doesNotExist())
                .andReturn();
        String documentId = JsonPath.read(added.getResponse().getContentAsString(), "$.document.documentId");
        addDocument(id, "\"2\"", "other", null, "Insurance certificate 2026", pdf("insurance.pdf"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.document.label").value("Insurance certificate 2026"));

        mvc.perform(as(get(PATH + "/{id}/documents", id), CONNECTOR))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ETAG, "\"3\""))
                .andExpect(jsonPath("$.version").value("3"))
                .andExpect(jsonPath("$.documents[*].type").value(org.hamcrest.Matchers.contains("gtc", "other")));
        mvc.perform(as(get(PATH + "/{id}", id), CONNECTOR))
                .andExpect(jsonPath("$.gtc.document.documentId").value(documentId))
                .andExpect(jsonPath("$.otherDocuments[0].label").value("Insurance certificate 2026"));
        mvc.perform(as(get(PATH + "/{id}/documents/{documentId}", id, documentId), CONNECTOR))
                .andExpect(status().isOk())
                .andExpect(content().contentType("application/pdf"))
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"gtc.pdf\""))
                .andExpect(content().bytes(PDF));

        var replacement = new MockMultipartFile("file", "gtc-signed.png", "image/png",
                new byte[]{(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n', 7});
        mvc.perform(as(multipart(HttpMethod.PUT, PATH + "/{id}/documents/{documentId}", id, documentId), CONNECTOR)
                        .file(replacement).header(HttpHeaders.IF_MATCH, "\"3\""))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ETAG, "\"4\""))
                .andExpect(jsonPath("$.document.documentId").value(documentId))
                .andExpect(jsonPath("$.document.filename").value("gtc-signed.png"));
        problem(mvc.perform(as(multipart(HttpMethod.PUT, PATH + "/{id}/documents/{documentId}", id, documentId), CONNECTOR)
                .file(replacement).param("label", "GTC").header(HttpHeaders.IF_MATCH, "\"4\"")), 400, "invalid-document-upload");

        mvc.perform(as(delete(PATH + "/{id}/documents/{documentId}", id, documentId), CONNECTOR)
                        .header(HttpHeaders.IF_MATCH, "\"4\""))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ETAG, "\"5\""))
                .andExpect(jsonPath("$.version").value("5"));
        problem(mvc.perform(as(get(PATH + "/{id}/documents/{documentId}", id, documentId), CONNECTOR)), 404, "not-found");
        problem(mvc.perform(as(get(PATH + "/{id}/documents/{documentId}", id, "not-a-uuid"), CONNECTOR)), 404, "not-found");
    }

    @Test
    void aDocument_isRefused_whenItsFormOrFileIsNotValid() throws Exception {
        var id = created(CONNECTOR, sampleRequest());
        addDocument(id, "\"1\"", "gtc", null, null, pdf("gtc.pdf")).andExpect(status().isCreated());

        problem(addDocument(id, "\"2\"", "uca", null, null, pdf("uca.pdf")), 400, "invalid-document-upload")
                .andExpect(jsonPath("$.detail").value("useCaseId: required for a uca"))
                .andExpect(jsonPath("$.errors").doesNotExist());
        problem(addDocument(id, "\"2\"", "gtc", null, null, null), 400, "invalid-document-upload");
        problem(addDocument(id, "\"2\"", "registration-extract", null, null,
                new MockMultipartFile("file", "extract.pdf", "application/pdf", "<html>".getBytes())), 400,
                "invalid-document-upload");
        problem(addDocument(id, "\"2\"", "registration-extract", null, null,
                new MockMultipartFile("file", "extract.txt", "text/plain", PDF)), 415, "unsupported-document-type");
        var elevenMb = Arrays.copyOf(PDF, 10 * 1024 * 1024 + 1);
        problem(addDocument(id, "\"2\"", "registration-extract", null, null,
                new MockMultipartFile("file", "extract.pdf", "application/pdf", elevenMb)), 413, "content-too-large");
        problem(addDocument(id, "\"2\"", "uca", "engineering", null, pdf("uca.pdf")), 422, "use-case-not-in-request");
        problem(addDocument(id, "\"2\"", "gtc", null, null, pdf("gtc.pdf")), 409, "document-slot-taken")
                .andExpect(jsonPath("$.documentId").isNotEmpty());
        problem(addDocument(id, "\"1\"", "registration-extract", null, null, pdf("extract.pdf")), 409, "version-conflict");
    }

    @Test
    void aDraft_takesAtMost5OtherDocuments_ofAtMost50MbTogether() throws Exception {
        var id = created(CONNECTOR, sampleRequest());
        var tenMb = Arrays.copyOf(PDF, 10 * 1024 * 1024);
        for (int i = 1; i <= 5; i++) {
            addDocument(id, "\"" + i + "\"", "other", null, "Annex " + i,
                    new MockMultipartFile("file", "annex.pdf", "application/pdf", tenMb)).andExpect(status().isCreated());
        }

        problem(addDocument(id, "\"6\"", "other", null, "Annex 6", pdf("annex.pdf")), 422, "other-document-limit-reached");
        problem(addDocument(id, "\"6\"", "gtc", null, null, pdf("gtc.pdf")), 422, "document-quota-exceeded");
    }

    @Test
    void submit_checksTheDraftAsAWhole_thenFilesIt_andTheRequestIsReadOnly() throws Exception {
        var id = created(CONNECTOR, sampleRequest().replace("ext-0001", "ext-0002"));

        problem(submit(CONNECTOR, id, "\"1\""), 422, "onboarding-request-submit-rejected")
                .andExpect(jsonPath("$.errors[*].field").value(org.hamcrest.Matchers.contains("documents", "ucas[0]",
                        "ucas[1]")));

        var complete = completeSample();
        submit(CONNECTOR, complete, "\"4\"")
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ETAG, "\"5\""))
                .andExpect(jsonPath("$.status").value("SUBMITTED"))
                .andExpect(jsonPath("$.submittedAt").isNotEmpty())
                .andExpect(jsonPath("$.gtc.acceptedAt").isNotEmpty())
                .andExpect(jsonPath("$.ucas[0].document.filename").value("uca-1.pdf"));
        verify(review).submitted(any());
        verify(events).onboardingStarted(any());

        // sent again, even on the version it was submitted on: the request as submitted
        submit(CONNECTOR, complete, "\"4\"").andExpect(status().isOk()).andExpect(jsonPath("$.version").value("5"));
        problem(mvc.perform(as(put(PATH + "/{id}", complete), CONNECTOR).header(HttpHeaders.IF_MATCH, "\"5\"")
                .contentType(MediaType.APPLICATION_JSON).content(sampleRequest())), 422, "invalid-onboarding-request-state");
        problem(mvc.perform(as(delete(PATH + "/{id}", complete), CONNECTOR).header(HttpHeaders.IF_MATCH, "\"5\"")),
                422, "invalid-onboarding-request-state");
        problem(addDocument(complete, "\"5\"", "other", null, "Late", pdf("late.pdf")), 422,
                "invalid-onboarding-request-state");
        // its documents stay readable
        mvc.perform(as(get(PATH + "/{id}/documents", complete), CONNECTOR)).andExpect(status().isOk())
                .andExpect(jsonPath("$.documents.length()").value(3));
    }

    @Test
    void submit_refusesADidThatDoesNotResolve() throws Exception {
        when(didResolver.problem(DID)).thenReturn(java.util.Optional.of("no DID document could be fetched"));
        var id = completeSample();

        problem(submit(CONNECTOR, id, "\"4\""), 422, "onboarding-request-submit-rejected")
                .andExpect(jsonPath("$.errors[0].field").value("legalEntity.preferredDid"))
                .andExpect(jsonPath("$.errors[0].message").value("does not resolve: no DID document could be fetched"));
    }

    @Test
    void aConnector_hasAtMost10DraftsAnd10RequestsAwaitingADecision() throws Exception {
        for (int i = 0; i < 10; i++) {
            created(CONNECTOR, minimalRequest("did:web:acme-%d.example.com".formatted(i), "ref-" + i));
        }
        problem(create(CONNECTOR, minimalRequest("did:web:acme-10.example.com", "ref-10")), 422,
                "connector-draft-limit-reached");

        var other = "did:web:other.example.com";
        for (int i = 0; i <= 10; i++) {
            var id = created(other, minimalRequest("did:web:other-%d.example.com".formatted(i), "ref-" + i));
            var upload = as(multipart(PATH + "/{id}/documents", id), other).file(pdf("gtc.pdf")).param("type", "gtc")
                    .header(HttpHeaders.IF_MATCH, "\"1\"");
            mvc.perform(upload).andExpect(status().isCreated());
            var submitted = submit(other, id, "\"2\"");
            if (i < 10) {
                submitted.andExpect(status().isOk());
            } else {
                problem(submitted, 422, "connector-pending-request-limit-reached")
                        .andExpect(jsonPath("$.errors").doesNotExist());
            }
        }
    }

    @Test
    void delete_removesTheDraft() throws Exception {
        var id = created(CONNECTOR, sampleRequest());

        mvc.perform(as(delete(PATH + "/{id}", id), CONNECTOR).header(HttpHeaders.IF_MATCH, "\"1\""))
                .andExpect(status().isNoContent());
        problem(mvc.perform(as(get(PATH + "/{id}", id), CONNECTOR)), 404, "not-found");
    }

    @Test
    void anUnexpectedFailure_isAnInternalError() throws Exception {
        when(didResolver.problem(any())).thenThrow(new IllegalStateException("boom"));
        var id = completeSample();

        MvcResult result = submit(CONNECTOR, id, etag(CONNECTOR, id)).andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(500);
        assertThat(result.getResponse().getContentAsString()).contains("urn:decade-x:tsp:problem:internal-error")
                .doesNotContain("boom");
    }
}
