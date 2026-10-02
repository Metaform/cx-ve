package com.metaform.dxonboarding.adapter.out.issuer;

import com.metaform.dxonboarding.config.IssuerServiceProperties;
import com.metaform.dxonboarding.domain.model.onboarding.Address;
import com.metaform.dxonboarding.domain.model.onboarding.CompanyType;
import com.metaform.dxonboarding.domain.model.onboarding.LegalEntity;
import com.metaform.dxonboarding.domain.model.onboarding.OnboardingRequest;
import com.metaform.dxonboarding.domain.model.onboarding.OnboardingRequestData;
import com.metaform.dxonboarding.domain.model.onboarding.OnboardingStatus;
import java.time.Instant;
import java.util.List;
import java.util.Map;
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
 * The IssuerService admin API calls an approval makes: the holder registration (merging into a
 * holder another dataspace registered) and the credential offer.
 */
class IssuerServiceAdaptersTest {

    private static final String API = "http://issuer.test/api/admin";
    private static final String DID = "did:web:identity.cxve.localhost:acme";

    private final RestClient.Builder builder = RestClient.builder().baseUrl(API);
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    private final RestClient restClient = builder.build();
    private final IssuerServiceProperties properties = new IssuerServiceProperties(API, "issuer", "sudo",
            List.of("decadex-membership-credential-def"));
    private final IssuerServiceHolderRegistrationService holders = new IssuerServiceHolderRegistrationService(
            restClient, (resource, scopes) -> "token-for-%s-%s".formatted(resource, scopes), properties);
    private final IssuerServiceCredentialOfferService offers = new IssuerServiceCredentialOfferService(
            restClient, (resource, scopes) -> "token-for-%s-%s".formatted(resource, scopes), properties);

    private static OnboardingRequest approved() {
        var data = new OnboardingRequestData(new LegalEntity(DID, "Acme Corp", "DE",
                CompanyType.LIMITED_LIABILITY_COMPANY, null, null, null, List.of(),
                new Address("Street 1", null, "Berlin", "DE", null), null), null, null, null, null, null, null);
        return new OnboardingRequest("req-1", "DX-OR-000001", DID, Instant.now(), OnboardingStatus.APPROVAL_IN_PROGRESS,
                data, null, "DX-00000042", null, null, null, Map.of(), null);
    }

    @Test
    void registerHolder_registersTheDidWithItsDecadeXId() {
        server.expect(requestTo(API + "/v1/participants/issuer/holders"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer token-for-sudo-issuer-admin-api:admin"))
                .andExpect(content().json("""
                        {"holderId": "%s", "did": "%s", "name": "Acme Corp",
                         "properties": {"id": "%s", "decadeXId": "DX-00000042"}}""".formatted(DID, DID, DID), true))
                .andRespond(withStatus(HttpStatus.CREATED));

        holders.registerHolder(approved());
        server.verify();
    }

    @Test
    void registerHolder_mergesIntoAHolderAnotherDataspaceRegistered() {
        server.expect(requestTo(API + "/v1/participants/issuer/holders"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withStatus(HttpStatus.CONFLICT));
        // the holder id is a URI variable, so the DID's colons go out percent-encoded
        server.expect(requestTo(API + "/v1/participants/issuer/holders/" + DID.replace(":", "%3A")))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        {"holderId": "%s", "did": "%s", "holderName": "Acme (Catena-X)", "anonymous": false,
                         "properties": {"id": "%s", "bpn": "BPNL0000000000XY", "memberOf": "Catena-X",
                                        "contractVersion": "1.0"},
                         "lastModifiedAt": 0}""".formatted(DID, DID, DID), MediaType.APPLICATION_JSON));
        // the Catena-X claims and name stay: the Decade-X ones are added
        server.expect(requestTo(API + "/v1/participants/issuer/holders"))
                .andExpect(method(HttpMethod.PUT))
                .andExpect(content().json("""
                        {"holderId": "%s", "did": "%s", "name": "Acme (Catena-X)",
                         "properties": {"id": "%s", "bpn": "BPNL0000000000XY", "memberOf": "Catena-X",
                                        "contractVersion": "1.0", "decadeXId": "DX-00000042"}}"""
                        .formatted(DID, DID, DID), true))
                .andRespond(withSuccess());

        holders.registerHolder(approved());
        server.verify();
    }

    @Test
    void offerCredentials_offersTheConfiguredDefinitionsToTheHolder() {
        server.expect(requestTo(API + "/v1/participants/issuer/credentials/offer"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer token-for-sudo-issuer-admin-api:admin"))
                .andExpect(content().json("""
                        {"holderId": "%s", "credentials": ["decadex-membership-credential-def"]}""".formatted(DID), true))
                .andRespond(withSuccess());

        offers.offerCredentials(approved());
        server.verify();
    }
}
