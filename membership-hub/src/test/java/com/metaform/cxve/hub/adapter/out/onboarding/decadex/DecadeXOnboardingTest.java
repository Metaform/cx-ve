package com.metaform.cxve.hub.adapter.out.onboarding.decadex;

import com.metaform.cxve.hub.adapter.out.onboarding.ClientCredentials;
import com.metaform.cxve.hub.adapter.out.onboarding.RegistrationValidator;
import com.metaform.cxve.hub.config.DataspaceProperties;
import com.metaform.cxve.hub.domain.model.MemberData;
import com.metaform.cxve.hub.domain.model.Membership;
import com.metaform.cxve.hub.domain.model.RegistrationOutcome;
import com.metaform.cxve.hub.domain.port.DataspaceOnboarding.InvalidRegistrationException;
import jakarta.validation.Validation;
import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpRequest;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.ResponseActions;
import org.springframework.test.web.client.response.DefaultResponseCreator;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.headerDoesNotExist;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServiceUnavailable;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

/**
 * The DECADE-X half of a member request: the {@code registration} object is the TSP's onboarding
 * request data minus what the hub owns; it is filed as a draft, given placeholder documents and
 * submitted, each change under the draft's current version and the member's DID as connector
 * identity; the TSP's refusals are read from its problems; and the outcome is polled.
 */
@SuppressWarnings("unchecked")
class DecadeXOnboardingTest {

    private static final String API = "http://dx-onboarding.test/api/v1/onboarding-requests";
    private static final String DID = "did:web:identity.cxve.localhost:acme";
    private static final String SUT_DID = "did:web:sut.example.com";

    private final RestClient.Builder restClient = RestClient.builder();
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(restClient).build();
    private final DataspaceProperties.Onboarding settings = new DataspaceProperties.Onboarding(API,
            new DataspaceProperties.Client("http://idp.test/token", "hub", "secret", ""), null);
    private final DecadeXOnboarding onboarding = new DecadeXOnboarding(restClient, settings,
            new ClientCredentials(settings.auth()) {
                @Override
                public String getToken() {
                    return "hub-token";
                }
            },
            new RegistrationValidator(Validation.buildDefaultValidatorFactory().getValidator()));

    static Map<String, Object> registration() {
        var address = Map.of("street", "Beispielstrasse 1", "postalCode", "10115", "locality", "Berlin",
                "countryCode", "DE", "countryName", "Germany");
        var registration = new HashMap<String, Object>();
        registration.put("legalEntity", Map.of(
                "registrationCountry", "DE",
                "companyType", "LIMITED_LIABILITY_COMPANY",
                "registrationNumbers", List.of(Map.of("scheme", "vatID", "value", "DE123456789")),
                "legalAddress", address));
        registration.put("legalPerson", Map.of("fullName", "Erika Mustermann", "email", "erika@acme.example"));
        registration.put("businessSites", List.of(Map.of("siteName", "Headquarters", "mainAddress", address)));
        registration.put("gtc", Map.of("versionNumber", "1.0"));
        registration.put("ucas", List.of(
                Map.of("useCaseId", "export-control", "useCaseName", "Export Control", "versionNumber", "v1.0"),
                Map.of("useCaseId", "critical-supply-chain")));
        registration.put("declarations", Map.of("informationAccurate", true, "authorisedToAct", true,
                "evidenceMayBeRequested", true));
        return registration;
    }

    /** A member request as it arrives: no DECADE-X-ID, which the TSP assigns. */
    private static MemberData member(Map<String, Object> registration) {
        return new MemberData("decade-x", "Acme Corp", "acme", null, null, registration);
    }

    /** A member hosted here, with the DECADE-X-ID the hub generated for its deployment. */
    private static MemberData hosted() {
        return new MemberData("decade-x", "Acme Corp", "acme", "DX-00000001", null, registration());
    }

    /** A member running elsewhere: it brings its DID, and the TSP assigns its DECADE-X-ID. */
    private static MemberData external() {
        return new MemberData("decade-x", "SUT GmbH", "sut", null, SUT_DID, registration());
    }

    private static Membership submitted(String processId) {
        return Membership.submitted("ext-1", "decade-x", "Acme Corp", DID, "DX-00000001")
                .withOnboardingProcessId(processId);
    }

    // --- validation ---

    @Test
    void validate_acceptsTheTspOnboardingRequestData() {
        onboarding.validate(member(registration()));
        onboarding.validate(external());
    }

    @Test
    void validate_refusesACatenaXRegistration() {
        // one field only: which unknown field Jackson names first follows the map's iteration order
        assertThatThrownBy(() -> onboarding.validate(member(Map.of("city", "Munich"))))
                .isInstanceOf(InvalidRegistrationException.class)
                .hasMessageContaining("city");
    }

    @Test
    void validate_refusesWhatTheHubFillsInItself() {
        var registration = registration();
        registration.put("applicantReference", "mine");
        assertThatThrownBy(() -> onboarding.validate(member(registration)))
                .isInstanceOf(InvalidRegistrationException.class)
                .hasMessageContaining("applicantReference");

        var withName = registration();
        withName.put("legalEntity", with((Map<String, Object>) registration().get("legalEntity"), "legalName", "Other"));
        assertThatThrownBy(() -> onboarding.validate(member(withName)))
                .isInstanceOf(InvalidRegistrationException.class)
                .hasMessageContaining("legalName");
    }

    @Test
    void validate_refusesTheFieldsOfThePreviousApiVersion() {
        var gtc = registration();
        gtc.put("gtc", Map.of("versionId", "0b8e1f0e-5c0a-4a52-9a53-7a1d2c3b4e5f", "versionNumber", "1.0"));
        assertThatThrownBy(() -> onboarding.validate(member(gtc)))
                .isInstanceOf(InvalidRegistrationException.class)
                .hasMessageContaining("versionId");

        var uca = registration();
        uca.put("ucas", List.of(Map.of("useCaseId", "export-control", "accepted", true)));
        assertThatThrownBy(() -> onboarding.validate(member(uca)))
                .isInstanceOf(InvalidRegistrationException.class)
                .hasMessageContaining("accepted");
    }

    @Test
    void validate_refusesAUseCaseTheTspDoesNotKnow() {
        var registration = registration();
        registration.put("ucas", List.of(Map.of("useCaseId", "ccm", "versionNumber", "1.0")));

        assertThatThrownBy(() -> onboarding.validate(member(registration)))
                .isInstanceOf(InvalidRegistrationException.class)
                .hasMessageContaining("ccm")
                .hasMessageContaining("product-conformity");
    }

    @Test
    void validate_namesWhatIsMissingOrOutOfBounds() {
        var registration = registration();
        registration.remove("declarations");
        registration.put("legalPerson", Map.of("fullName", "Erika Mustermann", "email", "erika@acme.example",
                "phoneNumber", "+49 30 12345678901234567890123456"));
        registration.put("gtc", Map.of("versionNumber", ""));
        registration.put("ucas", List.of(Map.of("useCaseId", "export-control"),
                Map.of("useCaseId", "export-control", "versionNumber", "v".repeat(33))));

        assertThatThrownBy(() -> onboarding.validate(member(registration)))
                .isInstanceOf(InvalidRegistrationException.class)
                .hasMessageContaining("registration.declarations")
                .hasMessageContaining("registration.legalPerson.phoneNumber")
                .hasMessageContaining("registration.gtc.versionNumber")
                .hasMessageContaining("registration.ucas[1].versionNumber")
                .hasMessageContaining("registration.ucasDistinct");
    }

    @Test
    void validate_refusesADeclaredDecadeXId_whichTheTspAssigns() {
        assertThatThrownBy(() -> onboarding.validate(new MemberData("decade-x", "Acme Corp", "acme", "DX-00000001",
                null, registration())))
                .isInstanceOf(InvalidRegistrationException.class)
                .hasMessageContaining("memberId");
        assertThatThrownBy(() -> onboarding.validate(new MemberData("decade-x", "SUT GmbH", "sut", "DX-00000007",
                SUT_DID, registration())))
                .isInstanceOf(InvalidRegistrationException.class)
                .hasMessageContaining("assigned by the TSP");
    }

    @Test
    void validate_refusesADecadeXIdInTheRegistrationObject() {
        var registration = registration();
        registration.put("legalEntity", with((Map<String, Object>) registration.get("legalEntity"),
                "legalEntityId", "DX-00000001"));

        assertThatThrownBy(() -> onboarding.validate(member(registration)))
                .isInstanceOf(InvalidRegistrationException.class)
                .hasMessageContaining("legalEntityId");
    }

    @Test
    void validate_refusesANameLongerThanTheTspsLegalName() {
        assertThatThrownBy(() -> onboarding.validate(new MemberData("decade-x", "A".repeat(256), "acme", null, null,
                registration())))
                .isInstanceOf(InvalidRegistrationException.class)
                .hasMessageContaining("legalName");
    }

    @Test
    void validate_requiresTheOtherCompanyTypeToBeNamed() {
        var registration = registration();
        registration.put("legalEntity", with((Map<String, Object>) registration.get("legalEntity"),
                "companyType", "OTHER"));

        assertThatThrownBy(() -> onboarding.validate(member(registration)))
                .isInstanceOf(InvalidRegistrationException.class)
                .hasMessageContaining("registration.legalEntity.companyTypeOtherGiven");
    }

    // --- the hosted member's DECADE-X-ID ---

    @Test
    void aHostedMember_getsARandomDecadeXId() {
        var ids = IntStream.range(0, 20).mapToObj(i -> onboarding.memberIdForHostedMember().orElseThrow()).toList();

        assertThat(ids).allMatch(id -> id.matches(DecadeXOnboarding.DECADE_X_ID));
        assertThat(new HashSet<>(ids)).hasSizeGreaterThan(1);
    }

    @Test
    void issuerProperties_carryTheDecadeXId() {
        assertThat(onboarding.issuerProperties(DID, hosted()))
                .containsEntry("id", DID)
                .containsEntry("memberOf", "DECADE-X")
                .containsEntry("decadeXId", "DX-00000001")
                .containsEntry("bpn", "DX-00000001");
    }

    // --- draft, documents, submit ---

    @Test
    void submitRegistration_createsTheDraft_addsThePlaceholders_andSubmitsIt() {
        expect(HttpMethod.POST, API, DID)
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer hub-token"))
                .andExpect(header(DecadeXOnboarding.IDEMPOTENCY_KEY_HEADER, "dx-create-ext-1"))
                .andExpect(headerDoesNotExist(HttpHeaders.IF_MATCH))
                .andExpect(request -> {
                    assertThat(request.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_JSON);
                    assertThat(body(request)).contains("\"legalName\":\"Acme Corp\"", "\"preferredDid\":\"" + DID + "\"",
                                    "\"applicantReference\":\"ext-1\"", "\"registrationCountry\":\"DE\"",
                                    "\"gtc\":{\"versionNumber\":\"1.0\"}", "\"useCaseId\":\"export-control\"",
                                    // hosted here: the DECADE-X-ID its deployment was provisioned with
                                    "\"legalEntityId\":\"DX-00000001\"")
                            // the TSP names the use case itself
                            .doesNotContain("useCaseName");
                })
                .andRespond(json(HttpStatus.CREATED, "1", draft("req-1", "DRAFT")));
        expect(HttpMethod.GET, API + "/req-1/documents", DID)
                .andRespond(json(HttpStatus.OK, "1", "{\"version\": \"1\", \"documents\": []}"));
        expect(HttpMethod.POST, API + "/req-1/documents", DID)
                .andExpect(header(HttpHeaders.IF_MATCH, "\"1\""))
                .andExpect(request -> {
                    assertThat(request.getHeaders().getContentType().isCompatibleWith(MediaType.MULTIPART_FORM_DATA)).isTrue();
                    assertThat(body(request)).contains("name=\"type\"", "\r\ngtc\r\n",
                                    "name=\"file\"; filename=\"gtc-placeholder.pdf\"", "Content-Type: application/pdf",
                                    "%PDF-1.4")
                            .doesNotContain("name=\"useCaseId\"");
                })
                .andRespond(json(HttpStatus.CREATED, "2", document("gtc", null)));
        expect(HttpMethod.POST, API + "/req-1/documents", DID)
                .andExpect(header(HttpHeaders.IF_MATCH, "\"2\""))
                .andExpect(request -> assertThat(body(request)).contains("\r\nuca\r\n", "name=\"useCaseId\"",
                        "\r\nexport-control\r\n", "filename=\"uca-export-control-placeholder.pdf\""))
                .andRespond(json(HttpStatus.CREATED, "3", document("uca", "export-control")));
        expect(HttpMethod.POST, API + "/req-1/documents", DID)
                .andExpect(header(HttpHeaders.IF_MATCH, "\"3\""))
                .andExpect(request -> assertThat(body(request)).contains("\r\ncritical-supply-chain\r\n"))
                .andRespond(json(HttpStatus.CREATED, "4", document("uca", "critical-supply-chain")));
        expect(HttpMethod.POST, API + "/req-1/submit", DID)
                .andExpect(header(HttpHeaders.IF_MATCH, "\"4\""))
                .andExpect(header(DecadeXOnboarding.IDEMPOTENCY_KEY_HEADER, "dx-submit-ext-1"))
                .andRespond(json(HttpStatus.OK, "5", draft("req-1", "SUBMITTED")));

        assertThat(onboarding.submitRegistration("ext-1", DID, hosted())).isEqualTo("req-1");
        server.verify();
    }

    @Test
    void submitRegistration_anExternalMember_sendsNoDecadeXId() {
        expect(HttpMethod.POST, API, SUT_DID)
                .andExpect(request -> assertThat(body(request)).contains("\"legalName\":\"SUT GmbH\"")
                        .doesNotContain("legalEntityId"))
                .andRespond(json(HttpStatus.CREATED, "1", draft("req-9", "DRAFT")));
        expect(HttpMethod.GET, API + "/req-9/documents", SUT_DID)
                .andRespond(json(HttpStatus.OK, "1", "{\"version\": \"1\", \"documents\": []}"));
        for (var version = 1; version <= 3; version++) {
            expect(HttpMethod.POST, API + "/req-9/documents", SUT_DID)
                    .andRespond(json(HttpStatus.CREATED, String.valueOf(version + 1), "{}"));
        }
        expect(HttpMethod.POST, API + "/req-9/submit", SUT_DID)
                .andRespond(json(HttpStatus.OK, "5", draft("req-9", "SUBMITTED")));

        assertThat(onboarding.submitRegistration("ext-9", SUT_DID, external())).isEqualTo("req-9");
        server.verify();
    }

    @Test
    void submitRegistration_resumesTheDraftAnEarlierAttemptStarted() {
        // the same key returns the first attempt's draft (200), with the GTC already on file
        expect(HttpMethod.POST, API, DID)
                .andRespond(json(HttpStatus.OK, "2", draft("req-1", "DRAFT")));
        expect(HttpMethod.GET, API + "/req-1/documents", DID)
                .andRespond(json(HttpStatus.OK, "2", "{\"version\": \"2\", \"documents\": [" + document("gtc", null) + "]}"));
        expect(HttpMethod.POST, API + "/req-1/documents", DID)
                .andExpect(header(HttpHeaders.IF_MATCH, "\"2\""))
                .andExpect(request -> assertThat(body(request)).contains("\r\nexport-control\r\n"))
                .andRespond(json(HttpStatus.CREATED, "3", "{}"));
        expect(HttpMethod.POST, API + "/req-1/documents", DID)
                .andExpect(request -> assertThat(body(request)).contains("\r\ncritical-supply-chain\r\n"))
                .andRespond(json(HttpStatus.CREATED, "4", "{}"));
        expect(HttpMethod.POST, API + "/req-1/submit", DID)
                .andExpect(header(HttpHeaders.IF_MATCH, "\"4\""))
                .andRespond(json(HttpStatus.OK, "5", draft("req-1", "SUBMITTED")));

        assertThat(onboarding.submitRegistration("ext-1", DID, hosted())).isEqualTo("req-1");
        server.verify();
    }

    @Test
    void submitRegistration_aRequestAnEarlierAttemptSubmitted_isTakenAsItIs() {
        expect(HttpMethod.POST, API, DID)
                .andRespond(json(HttpStatus.OK, "5", draft("req-1", "UNDER_REVIEW")));

        assertThat(onboarding.submitRegistration("ext-1", DID, hosted())).isEqualTo("req-1");
        server.verify();
    }

    @Test
    void submitRegistration_aSlotAlreadyTaken_isSkipped_andAVersionConflict_readsTheDraftAgain() {
        expect(HttpMethod.POST, API, DID).andRespond(json(HttpStatus.CREATED, "1", draft("req-1", "DRAFT")));
        expect(HttpMethod.GET, API + "/req-1/documents", DID)
                .andRespond(json(HttpStatus.OK, "1", "{\"version\": \"1\", \"documents\": []}"));
        // an upload whose response was lost went through after all
        expect(HttpMethod.POST, API + "/req-1/documents", DID)
                .andRespond(problem(HttpStatus.CONFLICT, "document-slot-taken", "\"documentId\": \"doc-gtc\""));
        expect(HttpMethod.GET, API + "/req-1/documents", DID)
                .andRespond(json(HttpStatus.OK, "2", "{\"version\": \"2\", \"documents\": [" + document("gtc", null) + "]}"));
        expect(HttpMethod.POST, API + "/req-1/documents", DID)
                .andExpect(header(HttpHeaders.IF_MATCH, "\"2\""))
                .andRespond(problem(HttpStatus.CONFLICT, "version-conflict", null));
        expect(HttpMethod.GET, API + "/req-1/documents", DID)
                .andRespond(json(HttpStatus.OK, "3", "{\"version\": \"3\", \"documents\": [" + document("gtc", null) + "]}"));
        expect(HttpMethod.POST, API + "/req-1/documents", DID)
                .andExpect(header(HttpHeaders.IF_MATCH, "\"3\""))
                .andExpect(request -> assertThat(body(request)).contains("\r\nexport-control\r\n"))
                .andRespond(json(HttpStatus.CREATED, "4", "{}"));
        expect(HttpMethod.POST, API + "/req-1/documents", DID)
                .andExpect(header(HttpHeaders.IF_MATCH, "\"4\""))
                .andRespond(json(HttpStatus.CREATED, "5", "{}"));
        expect(HttpMethod.POST, API + "/req-1/submit", DID)
                .andExpect(header(HttpHeaders.IF_MATCH, "\"5\""))
                .andRespond(json(HttpStatus.OK, "6", draft("req-1", "SUBMITTED")));

        assertThat(onboarding.submitRegistration("ext-1", DID, hosted())).isEqualTo("req-1");
        server.verify();
    }

    @Test
    void submitRegistration_triesOnceMore_whenTheTspCannotBeReached() {
        expect(HttpMethod.POST, API, DID).andRespond(withServiceUnavailable());
        expect(HttpMethod.POST, API, DID)
                .andExpect(header(DecadeXOnboarding.IDEMPOTENCY_KEY_HEADER, "dx-create-ext-1"))
                .andRespond(json(HttpStatus.CREATED, "1", draft("req-1", "DRAFT")));
        expect(HttpMethod.GET, API + "/req-1/documents", DID).andRespond(withException(new IOException("reset")));
        expect(HttpMethod.GET, API + "/req-1/documents", DID)
                .andRespond(json(HttpStatus.OK, "1", "{\"version\": \"1\", \"documents\": []}"));
        for (var version = 1; version <= 3; version++) {
            expect(HttpMethod.POST, API + "/req-1/documents", DID)
                    .andRespond(json(HttpStatus.CREATED, String.valueOf(version + 1), "{}"));
        }
        expect(HttpMethod.POST, API + "/req-1/submit", DID)
                .andRespond(json(HttpStatus.OK, "5", draft("req-1", "SUBMITTED")));

        assertThat(onboarding.submitRegistration("ext-1", DID, hosted())).isEqualTo("req-1");
        server.verify();
    }

    @Test
    void submitRegistration_aSubmitTheTspRefuses_isAnInvalidRegistration_andTheDraftIsDeleted() {
        expect(HttpMethod.POST, API, SUT_DID).andRespond(json(HttpStatus.CREATED, "1", draft("req-9", "DRAFT")));
        expect(HttpMethod.GET, API + "/req-9/documents", SUT_DID)
                .andRespond(json(HttpStatus.OK, "1", "{\"version\": \"1\", \"documents\": []}"));
        for (var version = 1; version <= 3; version++) {
            expect(HttpMethod.POST, API + "/req-9/documents", SUT_DID)
                    .andRespond(json(HttpStatus.CREATED, String.valueOf(version + 1), "{}"));
        }
        expect(HttpMethod.POST, API + "/req-9/submit", SUT_DID)
                .andRespond(problem(HttpStatus.UNPROCESSABLE_CONTENT, "onboarding-request-submit-rejected", """
                        "errors": [{"field": "legalEntity.preferredDid",
                                    "message": "does not resolve: no DID document could be fetched"}]"""));
        expect(HttpMethod.GET, API + "/req-9", SUT_DID).andRespond(json(HttpStatus.OK, "4", draft("req-9", "DRAFT")));
        expect(HttpMethod.DELETE, API + "/req-9", SUT_DID)
                .andExpect(header(HttpHeaders.IF_MATCH, "\"4\""))
                .andRespond(withStatus(HttpStatus.NO_CONTENT));

        assertThatThrownBy(() -> onboarding.submitRegistration("ext-9", SUT_DID, external()))
                .isInstanceOf(InvalidRegistrationException.class)
                .hasMessageContaining("refused by the TSP")
                .hasMessageContaining("legalEntity.preferredDid does not resolve: no DID document could be fetched");
        server.verify();
    }

    @Test
    void submitRegistration_aDraftTheTspRefuses_isAnInvalidRegistration() {
        expect(HttpMethod.POST, API, SUT_DID)
                .andRespond(problem(HttpStatus.BAD_REQUEST, "invalid-request", """
                        "errors": [{"field": "legalPerson.email", "message": "not an email address"}]"""));

        assertThatThrownBy(() -> onboarding.submitRegistration("ext-9", SUT_DID, external()))
                .isInstanceOf(InvalidRegistrationException.class)
                .hasMessageContaining("legalPerson.email not an email address");
        server.verify();
    }

    @Test
    void submitRegistration_aQuota_isTheTspsLimit_notAnInvalidRegistration() {
        expect(HttpMethod.POST, API, SUT_DID).andRespond(problem(HttpStatus.UNPROCESSABLE_CONTENT,
                "connector-draft-limit-reached", "\"detail\": \"The participant already has 10 drafts\""));

        assertThatThrownBy(() -> onboarding.submitRegistration("ext-9", SUT_DID, external()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("create the onboarding request")
                .hasMessageContaining("already has 10 drafts");
        server.verify();
    }

    // --- status ---

    @Test
    void pollStatus_mapsTheTspStatus() {
        expectStatus("req-1", """
                {"id": "req-1", "status": "APPROVED", "legalEntity": {"legalEntityId": "DX-00000042"}}""");
        expectStatus("req-2", """
                {"id": "req-2", "status": "REJECTED",
                 "decision": {"rejectReasonCode": "INVALID_LEGAL_ENTITY", "rejectComment": "Unknown VAT ID",
                              "resubmissionAllowed": true}}""");
        expectStatus("req-3", """
                {"id": "req-3", "status": "UNDER_REVIEW", "legalEntity": {"legalName": "Acme Corp"}}""");
        expectStatus("req-4", """
                {"id": "req-4", "status": "DRAFT"}""");

        // approved: holder registered and offered its credentials, under the DECADE-X-ID it now holds
        assertThat(onboarding.pollStatus(submitted("req-1")))
                .isEqualTo(new RegistrationOutcome("ext-1", RegistrationOutcome.Status.CONFIRMED, null, "DX-00000042"));
        assertThat(onboarding.pollStatus(submitted("req-2"))).isEqualTo(new RegistrationOutcome("ext-1",
                RegistrationOutcome.Status.DECLINED, "INVALID_LEGAL_ENTITY: Unknown VAT ID"));
        assertThat(onboarding.pollStatus(submitted("req-3")))
                .isEqualTo(new RegistrationOutcome("ext-1", RegistrationOutcome.Status.PENDING, null));
        assertThat(onboarding.pollStatus(submitted("req-4")))
                .isEqualTo(new RegistrationOutcome("ext-1", RegistrationOutcome.Status.PENDING, null));
        server.verify();
    }

    @Test
    void pollStatus_aFailedProvisioningDeclinesTheMembership() {
        expectStatus("req-4", """
                {"id": "req-4", "status": "APPROVAL_FAILED",
                 "decision": {"rejectComment": "Credential issuance failed: holder DID does not resolve"}}""");

        assertThat(onboarding.pollStatus(submitted("req-4"))).isEqualTo(new RegistrationOutcome("ext-1",
                RegistrationOutcome.Status.DECLINED,
                "Approved, but provisioning the membership failed: Credential issuance failed: holder DID does not resolve"));
        server.verify();
    }

    @Test
    void theTspHasNoCallbacks() {
        assertThat(onboarding.pollsStatus()).isTrue();
        onboarding.registerCallback();
        assertThatThrownBy(() -> onboarding.readCallback(Map.of())).isInstanceOf(UnsupportedOperationException.class);
    }

    private ResponseActions expect(HttpMethod method, String url, String connectorId) {
        return server.expect(requestTo(url))
                .andExpect(method(method))
                .andExpect(header(DecadeXOnboarding.CONNECTOR_ID_HEADER, connectorId));
    }

    private void expectStatus(String requestId, String response) {
        expect(HttpMethod.GET, API + "/" + requestId, DID).andRespond(json(HttpStatus.OK, "1", response));
    }

    private static DefaultResponseCreator json(HttpStatus status, String version, String body) {
        return withStatus(status).contentType(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.ETAG, "\"" + version + "\"").body(body);
    }

    private static DefaultResponseCreator problem(HttpStatus status, String code, String members) {
        return withStatus(status).contentType(MediaType.APPLICATION_PROBLEM_JSON).body("""
                {"type": "urn:decade-x:tsp:problem:%s", "title": "Refused", "status": %d%s}"""
                .formatted(code, status.value(), members == null ? "" : ", " + members));
    }

    private static String draft(String id, String status) {
        return """
                {"id": "%s", "version": "1", "businessId": "DX-OR-000001", "status": "%s"}""".formatted(id, status);
    }

    private static String document(String type, String useCaseId) {
        return """
                {"documentId": "doc-%s", "type": "%s"%s, "filename": "%s.pdf"}""".formatted(type, type,
                useCaseId == null ? "" : ", \"useCaseId\": \"" + useCaseId + "\"", type);
    }

    private static Map<String, Object> with(Map<String, Object> map, String key, Object value) {
        var copy = new HashMap<>(map);
        copy.put(key, value);
        return copy;
    }

    private static String body(ClientHttpRequest request) {
        return ((MockClientHttpRequest) request).getBodyAsString();
    }
}
