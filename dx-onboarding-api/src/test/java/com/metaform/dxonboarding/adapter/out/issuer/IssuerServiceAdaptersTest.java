package com.metaform.dxonboarding.adapter.out.issuer;

import com.metaform.dxonboarding.config.IssuerServiceProperties;
import com.metaform.dxonboarding.domain.model.onboarding.Address;
import com.metaform.dxonboarding.domain.model.onboarding.BusinessSite;
import com.metaform.dxonboarding.domain.model.onboarding.OnboardingRequest;
import com.metaform.dxonboarding.domain.model.onboarding.OnboardingRequestData;
import com.metaform.dxonboarding.domain.model.onboarding.RegistrationNumber;
import com.metaform.dxonboarding.domain.model.onboarding.Requests;
import java.time.Instant;
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
            new IssuerServiceProperties.CredentialDefinitions("dx-legal-entity-credential-def",
                    "dx-site-credential-def"));
    private final IssuerServiceHolderRegistrationService holders = new IssuerServiceHolderRegistrationService(
            restClient, (resource, scopes) -> "token-for-%s-%s".formatted(resource, scopes), properties);
    private final IssuerServiceCredentialOfferService offers = new IssuerServiceCredentialOfferService(
            restClient, (resource, scopes) -> "token-for-%s-%s".formatted(resource, scopes), properties);

    /** The partners' JSON-LD context, as the examples' credentials carry it. */
    private static final String PARTNER_CONTEXT = """
            {"id": "@id", "type": "@type",
             "@vocab": "https://vocabulary.decade-x.org/ontology/participant/0#",
             "dx": "https://vocabulary.decade-x.org/ontology/participant/0#",
             "gx": "https://w3id.org/gaia-x/2411#", "vcard": "https://www.w3.org/2006/vcard/ns#",
             "legalAddress": "gx:legalAddress", "countryName": "gx:countryName", "countryCode": "gx:countryCode",
             "streetAddress": "vcard:street-address", "locality": "vcard:locality",
             "postalCode": "vcard:postal-code"}""";

    /** The Legal Entity VC's partner of {@link Requests#data}'s Acme Corp under DX-00000042. */
    private static final String LEGAL_ENTITY = """
            {"@context": %s, "id": "dx:DX-00000042", "type": "LegalEntity", "dx:name": "Acme Corp",
             "partnerID": "DX-00000042", "registrationNumber": [{"vatID": "DE123456789"}],
             "legalAddress": {"type": "gx:Address", "countryName": "Germany",
                              "countryCode": {"type": "gx:CountryNameAlpha2", "code": "DE"},
                              "streetAddress": "Beispielstrasse 1", "postalCode": "10115", "locality": "Berlin"}}"""
            .formatted(PARTNER_CONTEXT);

    private static OnboardingRequest approved() {
        return approved(Requests.data(DID));
    }

    private static OnboardingRequest approved(OnboardingRequestData data) {
        return OnboardingRequest.draft("req-1", "DX-OR-000001", DID, data, null, Instant.now())
                .submitted(Instant.now(), null)
                .approvalInProgress("DX-00000042");
    }

    /** {@link Requests#data} with two business sites: only the first is issued a Site VC. */
    private static OnboardingRequestData withSites() {
        var data = Requests.data(DID);
        var hamburg = new BusinessSite("S-HAMBURG", "Acme Hamburg", null,
                new Address("Musterstraße 12", "20095", "Hamburg", "DE", "Germany"),
                new Address("Hafenstraße 1", "20359", "Hamburg", "DE", "Germany"),
                List.of(new RegistrationNumber(RegistrationNumber.Scheme.duns, "342519870")));
        var toulouse = new BusinessSite("S-TOULOUSE", "Acme Toulouse", null, Requests.ADDRESS, null, List.of());
        return new OnboardingRequestData(data.legalEntity(), data.legalPerson(), List.of(hamburg, toulouse),
                data.gtc(), data.ucas(), data.declarations(), data.applicantReference());
    }

    @Test
    void registerHolder_registersTheDidWithItsLegalEntity() {
        server.expect(requestTo(API + "/v1/participants/issuer/holders"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer token-for-sudo-issuer-admin-api:admin"))
                .andExpect(content().json("""
                        {"holderId": "%s", "did": "%s", "name": "Acme Corp",
                         "properties": {"id": "%s", "dxSubjectType": "dx:Wallet", "dxLegalEntity": %s}}"""
                        .formatted(DID, DID, DID, LEGAL_ENTITY), true))
                .andRespond(withStatus(HttpStatus.CREATED));

        holders.registerHolder(approved());
        server.verify();
    }

    @Test
    void registerHolder_addsTheFirstBusinessSiteOwnedByTheLegalEntity() {
        server.expect(requestTo(API + "/v1/participants/issuer/holders"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().json("""
                        {"holderId": "%s", "did": "%s", "name": "Acme Corp",
                         "properties": {"id": "%s", "dxSubjectType": "dx:Wallet", "dxLegalEntity": %s,
                           "dxSite": {"@context": %s, "id": "dx:S-HAMBURG", "type": "BusinessSite",
                             "siteName": "Acme Hamburg", "partnerID": "S-HAMBURG",
                             "registrationNumber": [{"duns": "342519870"}],
                             "ownedBy_Dx": {"id": "dx:DX-00000042", "type": "LegalEntity"},
                             "mainAddress": {"type": "gx:Address", "countryName": "Germany",
                                             "countryCode": {"type": "gx:CountryNameAlpha2", "code": "DE"},
                                             "streetAddress": "Musterstraße 12", "postalCode": "20095",
                                             "locality": "Hamburg"},
                             "secondaryAddress": {"type": "gx:Address", "countryName": "Germany",
                                                  "countryCode": {"type": "gx:CountryNameAlpha2", "code": "DE"},
                                                  "streetAddress": "Hafenstraße 1", "postalCode": "20359",
                                                  "locality": "Hamburg"}}}}"""
                        .formatted(DID, DID, DID, LEGAL_ENTITY, PARTNER_CONTEXT), true))
                .andRespond(withStatus(HttpStatus.CREATED));

        holders.registerHolder(approved(withSites()));
        server.verify();
    }

    @Test
    void registerHolder_derivesTheSitePartnerIdWhenTheSiteHasNone() {
        var data = Requests.data(DID);
        var site = new BusinessSite(null, "Acme Berlin", null, Requests.ADDRESS, null, List.of());
        var request = approved(new OnboardingRequestData(data.legalEntity(), data.legalPerson(), List.of(site),
                data.gtc(), data.ucas(), data.declarations(), data.applicantReference()));
        server.expect(requestTo(API + "/v1/participants/issuer/holders"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().json("""
                        {"properties": {"dxSite": {"id": "dx:DX-00000042-S1", "partnerID": "DX-00000042-S1"}}}"""))
                .andRespond(withStatus(HttpStatus.CREATED));

        holders.registerHolder(request);
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
                                        "contractVersion": "1.0", "dxSite": {"partnerID": "S-STALE"}},
                         "lastModifiedAt": 0}""".formatted(DID, DID, DID), MediaType.APPLICATION_JSON));
        // the Catena-X claims and name stay: the DECADE-X ones are added, and a site this request
        // no longer declares is dropped
        server.expect(requestTo(API + "/v1/participants/issuer/holders"))
                .andExpect(method(HttpMethod.PUT))
                .andExpect(content().json("""
                        {"holderId": "%s", "did": "%s", "name": "Acme (Catena-X)",
                         "properties": {"id": "%s", "bpn": "BPNL0000000000XY", "memberOf": "Catena-X",
                                        "contractVersion": "1.0", "dxSubjectType": "dx:Wallet",
                                        "dxLegalEntity": %s}}"""
                        .formatted(DID, DID, DID, LEGAL_ENTITY), true))
                .andRespond(withSuccess());

        holders.registerHolder(approved());
        server.verify();
    }

    @Test
    void offerCredentials_offersBothCredentialsToAHolderWithASite() {
        server.expect(requestTo(API + "/v1/participants/issuer/credentials/offer"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer token-for-sudo-issuer-admin-api:admin"))
                .andExpect(content().json("""
                        {"holderId": "%s", "credentials": ["dx-legal-entity-credential-def", "dx-site-credential-def"]}"""
                        .formatted(DID), true))
                .andRespond(withSuccess());

        offers.offerCredentials(approved(withSites()));
        server.verify();
    }

    @Test
    void offerCredentials_offersOnlyTheLegalEntityCredentialToAHolderWithoutASite() {
        server.expect(requestTo(API + "/v1/participants/issuer/credentials/offer"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().json("""
                        {"holderId": "%s", "credentials": ["dx-legal-entity-credential-def"]}""".formatted(DID), true))
                .andRespond(withSuccess());

        offers.offerCredentials(approved());
        server.verify();
    }
}
