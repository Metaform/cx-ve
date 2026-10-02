package com.metaform.cxve.adapter.out.issuer;

import com.metaform.cxve.domain.model.CompanyRoleId;
import com.metaform.cxve.domain.model.OnboardingProcess;
import com.metaform.cxve.domain.model.PartnerRegistrationData;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * The holder registration: a new DID becomes a holder with the Catena-X properties; a DID that
 * already is one — from an earlier attempt, or from another dataspace — gets them merged in.
 */
class IssuerServiceHolderRegistrationServiceTest {

    private static final String API = "http://issuer.test/api/admin";
    private static final String DID = "did:web:sut.example.com";
    private static final String HOLDERS = API + "/v1/participants/issuer/holders";

    private final RestClient.Builder builder = RestClient.builder().baseUrl(API);
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    private final IssuerServiceHolderRegistrationService holders = new IssuerServiceHolderRegistrationService(
            (resource, scopes) -> "token-for-%s-%s".formatted(resource, scopes), builder.build(), "issuer", "sudo",
            "Catena-X");

    private static PartnerRegistrationData registration() {
        return new PartnerRegistrationData("ext-1", "Acme Corp", "Berlin", "Musterstrasse", "DE", "BE",
                List.of(CompanyRoleId.ACTIVE_PARTICIPANT), List.of(), List.of(), "BPNL0000000000XY", "Acme", null, null,
                null, DID, null, null);
    }

    private static OnboardingProcess process() {
        return OnboardingProcess.submitted("process-1", "ext-1", "BPNL0000000000XY", DID);
    }

    @Test
    void aNewDid_isRegisteredWithTheCatenaXProperties() {
        server.expect(requestTo(HOLDERS))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer token-for-sudo-issuer-admin-api:admin"))
                .andExpect(content().json("""
                        {"did": "%s", "holderId": "%s", "name": "Acme Corp",
                         "properties": {"id": "%s", "contractVersion": "1.0", "memberOf": "Catena-X",
                                        "bpn": "BPNL0000000000XY"}}""".formatted(DID, DID, DID), true))
                .andRespond(withStatus(HttpStatus.CREATED));

        holders.registerHolder(process(), registration());
        server.verify();
    }

    @Test
    void aHolderAnotherDataspaceRegistered_getsTheCatenaXPropertiesMergedIn() {
        expectConflictThenExisting("""
                {"holderId": "%s", "did": "%s", "holderName": "SUT GmbH (Decade-X)", "anonymous": false,
                 "properties": {"id": "%s", "decadeXId": "DX-00000042"}, "lastModifiedAt": 0}""");
        // the Decade-X claim and the name stay: without the Catena-X properties, the Catena-X
        // credentials would fail to generate
        server.expect(requestTo(HOLDERS))
                .andExpect(method(HttpMethod.PUT))
                .andExpect(content().json("""
                        {"did": "%s", "holderId": "%s", "name": "SUT GmbH (Decade-X)",
                         "properties": {"id": "%s", "decadeXId": "DX-00000042", "contractVersion": "1.0",
                                        "memberOf": "Catena-X", "bpn": "BPNL0000000000XY"}}""".formatted(DID, DID, DID),
                        true))
                .andRespond(withSuccess());

        holders.registerHolder(process(), registration());
        server.verify();
    }

    @Test
    void anEarlierAttemptsHolder_getsItsStaleBpnReplaced() {
        expectConflictThenExisting("""
                {"holderId": "%s", "did": "%s", "holderName": "Acme Corp",
                 "properties": {"id": "%s", "contractVersion": "1.0", "memberOf": "Catena-X",
                                "bpn": "BPNLOLD000000001"}}""");
        // a dead registration freed the DID, and this one corrected the BPN: the credentials must
        // carry the new one
        server.expect(requestTo(HOLDERS))
                .andExpect(method(HttpMethod.PUT))
                .andExpect(content().json("""
                        {"did": "%s", "holderId": "%s", "name": "Acme Corp",
                         "properties": {"id": "%s", "contractVersion": "1.0", "memberOf": "Catena-X",
                                        "bpn": "BPNL0000000000XY"}}""".formatted(DID, DID, DID), true))
                .andRespond(withSuccess());

        holders.registerHolder(process(), registration());
        server.verify();
    }

    private void expectConflictThenExisting(String existingTemplate) {
        server.expect(requestTo(HOLDERS))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withStatus(HttpStatus.CONFLICT));
        // the holder id is a URI variable, so the DID's colons go out percent-encoded
        server.expect(requestTo(HOLDERS + "/" + DID.replace(":", "%3A")))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("Authorization", "Bearer token-for-sudo-issuer-admin-api:admin"))
                .andRespond(withSuccess(existingTemplate.formatted(DID, DID, DID), MediaType.APPLICATION_JSON));
    }
}
