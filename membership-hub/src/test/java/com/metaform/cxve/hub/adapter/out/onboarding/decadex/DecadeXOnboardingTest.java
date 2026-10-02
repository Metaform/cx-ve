package com.metaform.cxve.hub.adapter.out.onboarding.decadex;

import com.metaform.cxve.hub.adapter.out.onboarding.ClientCredentials;
import com.metaform.cxve.hub.adapter.out.onboarding.RegistrationValidator;
import com.metaform.cxve.hub.config.DataspaceProperties;
import com.metaform.cxve.hub.domain.model.MemberData;
import com.metaform.cxve.hub.domain.model.Membership;
import com.metaform.cxve.hub.domain.model.RegistrationOutcome;
import com.metaform.cxve.hub.domain.port.DataspaceOnboarding.InvalidRegistrationException;
import jakarta.validation.Validation;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * The Decade-X half of a member request: the {@code registration} object is the TSP's onboarding
 * request minus what the hub owns, it is submitted as a multipart request with placeholder
 * documents under the member's DID as connector identity, and the outcome is polled.
 */
@SuppressWarnings("unchecked")
class DecadeXOnboardingTest {

    private static final String API = "http://dx-onboarding.test";
    private static final String DID = "did:web:acme";

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
        registration.put("gtc", Map.of("versionId", "0b8e1f0e-5c0a-4a52-9a53-7a1d2c3b4e5f", "versionNumber", "1.0",
                "accepted", true));
        registration.put("ucas", List.of(
                Map.of("useCaseId", "export-control", "useCaseName", "Export Control", "versionNumber", "v1.0",
                        "accepted", true),
                Map.of("useCaseId", "critical-supply-chain", "accepted", true)));
        registration.put("declarations", Map.of("informationAccurate", true, "authorisedToAct", true,
                "evidenceMayBeRequested", true));
        return registration;
    }

    private static MemberData member(Map<String, Object> registration) {
        return new MemberData("decade-x", "Acme Corp", "acme", "DX-00000001", null, registration);
    }

    /** A member running elsewhere: it brings its DID, and the TSP assigns its Decade-X-ID. */
    private static MemberData external() {
        return new MemberData("decade-x", "SUT GmbH", "sut", null, "did:web:sut.example.com", registration());
    }

    private static Membership submitted(String processId) {
        return Membership.submitted("ext-1", "decade-x", "Acme Corp", DID, "DX-00000001")
                .withOnboardingProcessId(processId);
    }

    @Test
    void validate_acceptsTheTspOnboardingRequest() {
        onboarding.validate(member(registration()));
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

        var legalEntity = new HashMap<String, Object>((Map<String, Object>) registration().get("legalEntity"));
        legalEntity.put("legalName", "Other Corp");
        var withName = registration();
        withName.put("legalEntity", legalEntity);
        assertThatThrownBy(() -> onboarding.validate(member(withName)))
                .isInstanceOf(InvalidRegistrationException.class)
                .hasMessageContaining("legalName");
    }

    @Test
    void validate_namesWhatIsMissingOrNotAccepted() {
        var registration = registration();
        registration.remove("legalPerson");
        registration.put("gtc", Map.of("versionId", "0b8e1f0e-5c0a-4a52-9a53-7a1d2c3b4e5f", "versionNumber", "1.0",
                "accepted", false));
        registration.put("ucas", List.of(Map.of("useCaseId", "ccm", "accepted", true),
                Map.of("useCaseId", "ccm", "accepted", false)));
        registration.put("declarations", Map.of("informationAccurate", true, "authorisedToAct", false,
                "evidenceMayBeRequested", true));

        assertThatThrownBy(() -> onboarding.validate(member(registration)))
                .isInstanceOf(InvalidRegistrationException.class)
                .hasMessageContaining("registration.legalPerson")
                .hasMessageContaining("registration.gtc.accepted")
                .hasMessageContaining("registration.ucas[1].accepted")
                .hasMessageContaining("registration.ucasDistinct")
                .hasMessageContaining("registration.declarations.authorisedToAct");
    }

    @Test
    void validate_aMemberHostedHere_bringsItsDecadeXId() {
        assertThatThrownBy(() -> onboarding.validate(new MemberData("decade-x", "Acme Corp", "acme", null, null,
                registration())))
                .isInstanceOf(InvalidRegistrationException.class)
                .hasMessageContaining("memberId");
        assertThatThrownBy(() -> onboarding.validate(new MemberData("decade-x", "Acme Corp", "acme",
                "BPNL0000000000XY", null, registration())))
                .isInstanceOf(InvalidRegistrationException.class)
                .hasMessageContaining("Decade-X-ID");
    }

    @Test
    void validate_anExternalMember_declaresItsDecadeXId_orLeavesItToTheTsp() {
        onboarding.validate(external());
        onboarding.validate(new MemberData("decade-x", "Acme Corp", "acme", "DX-00000001", "did:web:sut.example.com",
                registration()));

        assertThatThrownBy(() -> onboarding.validate(new MemberData("decade-x", "Acme Corp", "acme",
                "BPNL0000000000XY", "did:web:sut.example.com", registration())))
                .isInstanceOf(InvalidRegistrationException.class)
                .hasMessageContaining("Decade-X-ID");
    }

    @Test
    void validate_refusesADecadeXIdInTheRegistrationObject() {
        var registration = registration();
        var legalEntity = new HashMap<String, Object>((Map<String, Object>) registration.get("legalEntity"));
        legalEntity.put("legalEntityId", "DX-00000001");
        registration.put("legalEntity", legalEntity);

        assertThatThrownBy(() -> onboarding.validate(member(registration)))
                .isInstanceOf(InvalidRegistrationException.class)
                .hasMessageContaining("legalEntityId");
    }

    @Test
    void validate_requiresTheOtherCompanyTypeToBeNamed() {
        var registration = registration();
        var legalEntity = new HashMap<String, Object>((Map<String, Object>) registration.get("legalEntity"));
        legalEntity.put("companyType", "OTHER");
        registration.put("legalEntity", legalEntity);

        assertThatThrownBy(() -> onboarding.validate(member(registration)))
                .isInstanceOf(InvalidRegistrationException.class)
                .hasMessageContaining("registration.legalEntity.companyTypeOtherGiven");
    }

    @Test
    void issuerProperties_carryTheDecadeXId() {
        assertThat(onboarding.issuerProperties(DID, member(registration())))
                .containsEntry("id", DID)
                .containsEntry("memberOf", "Decade-X")
                .containsEntry("decadeXId", "DX-00000001")
                .containsEntry("bpn", "DX-00000001");
    }

    @Test
    void submitRegistration_sendsTheCompletedRequestWithPlaceholderDocuments() {
        server.expect(requestTo(API + "/api/v1/onboarding-requests"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer hub-token"))
                .andExpect(header(DecadeXOnboarding.CONNECTOR_ID_HEADER, DID))
                .andExpect(request -> {
                    assertThat(request.getHeaders().getContentType().isCompatibleWith(MediaType.MULTIPART_FORM_DATA)).isTrue();
                    var body = body(request);
                    assertThat(body).contains("name=\"request\"", "\"legalName\":\"Acme Corp\"",
                            "\"preferredDid\":\"" + DID + "\"", "\"applicantReference\":\"ext-1\"",
                            "\"registrationCountry\":\"DE\"",
                            // hosted here: the Decade-X-ID its deployment was provisioned with
                            "\"legalEntityId\":\"DX-00000001\"");
                    assertThat(body).contains("name=\"gtcDocument\"; filename=\"gtc-placeholder.pdf\"",
                            "name=\"ucaDocument[export-control]\"", "name=\"ucaDocument[critical-supply-chain]\"",
                            "Content-Type: application/pdf", "%PDF-1.4");
                })
                .andRespond(withSuccess("""
                        {"id": "6f1c1c1e-0000-4000-8000-000000000001", "businessId": "DX-OR-000001",
                         "status": "SUBMITTED", "connectorId": "did:web:acme", "submittedAt": "2026-10-02T10:00:00Z"}""",
                        MediaType.APPLICATION_JSON));

        assertThat(onboarding.submitRegistration("ext-1", DID, member(registration())))
                .isEqualTo("6f1c1c1e-0000-4000-8000-000000000001");
        server.verify();
    }

    @Test
    void submitRegistration_anExternalMember_sendsTheDecadeXIdItDeclared() {
        server.expect(requestTo(API + "/api/v1/onboarding-requests"))
                .andExpect(request -> assertThat(body(request)).contains("\"legalEntityId\":\"DX-00000007\""))
                .andRespond(withSuccess("""
                        {"id": "req-8", "businessId": "DX-OR-000008", "status": "SUBMITTED"}""",
                        MediaType.APPLICATION_JSON));

        var declared = new MemberData("decade-x", "SUT GmbH", "sut", "DX-00000007", "did:web:sut.example.com",
                registration());
        assertThat(onboarding.submitRegistration("ext-8", "did:web:sut.example.com", declared)).isEqualTo("req-8");
        server.verify();
    }

    @Test
    void submitRegistration_anExternalMember_withoutADecadeXId_sendsNone() {
        server.expect(requestTo(API + "/api/v1/onboarding-requests"))
                .andExpect(header(DecadeXOnboarding.CONNECTOR_ID_HEADER, "did:web:sut.example.com"))
                .andExpect(request -> assertThat(body(request)).contains("\"legalName\":\"SUT GmbH\"")
                        .doesNotContain("legalEntityId"))
                .andRespond(withSuccess("""
                        {"id": "req-9", "businessId": "DX-OR-000009", "status": "SUBMITTED"}""",
                        MediaType.APPLICATION_JSON));

        assertThat(onboarding.submitRegistration("ext-9", "did:web:sut.example.com", external())).isEqualTo("req-9");
        server.verify();
    }

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

        // approved: holder registered and offered its credentials, under the Decade-X-ID it now holds
        assertThat(onboarding.pollStatus(submitted("req-1")))
                .isEqualTo(new RegistrationOutcome("ext-1", RegistrationOutcome.Status.CONFIRMED, null, "DX-00000042"));
        assertThat(onboarding.pollStatus(submitted("req-2"))).isEqualTo(new RegistrationOutcome("ext-1",
                RegistrationOutcome.Status.DECLINED, "INVALID_LEGAL_ENTITY: Unknown VAT ID"));
        assertThat(onboarding.pollStatus(submitted("req-3")))
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

    private void expectStatus(String requestId, String response) {
        server.expect(requestTo(API + "/api/v1/onboarding-requests/" + requestId))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header(DecadeXOnboarding.CONNECTOR_ID_HEADER, DID))
                .andRespond(withSuccess(response, MediaType.APPLICATION_JSON));
    }

    private static String body(org.springframework.http.client.ClientHttpRequest request) {
        return ((MockClientHttpRequest) request).getBodyAsString();
    }
}
