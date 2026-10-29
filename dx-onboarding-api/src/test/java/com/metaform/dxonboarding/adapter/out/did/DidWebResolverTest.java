package com.metaform.dxonboarding.adapter.out.did;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/** A DID resolves when its did:web document is where the method puts it, and declares the DID. */
class DidWebResolverTest {

    private static final String DID = "did:web:identity.cxve.localhost:acme";

    private final RestClient.Builder builder = RestClient.builder();
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    private final DidWebResolver resolver = new DidWebResolver(builder.build(), "http");

    @Test
    void theDocumentUrl_followsTheDidWebMethod() {
        assertThat(resolver.documentUrl("did:web:acme.example.com"))
                .isEqualTo("http://acme.example.com/.well-known/did.json");
        assertThat(resolver.documentUrl("did:web:identity.cxve.localhost:acme"))
                .isEqualTo("http://identity.cxve.localhost/acme/did.json");
        assertThat(resolver.documentUrl("did:web:localhost%3A8080:users:acme"))
                .isEqualTo("http://localhost:8080/users/acme/did.json");
        assertThat(new DidWebResolver(builder.build(), "https").documentUrl("did:web:acme.example.com"))
                .isEqualTo("https://acme.example.com/.well-known/did.json");
    }

    @Test
    void aDidWhoseDocumentDeclaresIt_resolves() {
        server.expect(requestTo("http://identity.cxve.localhost/acme/did.json"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        {"id": "did:web:identity.cxve.localhost:Acme", "service": []}""", MediaType.APPLICATION_JSON));

        assertThat(resolver.problem(DID)).isEmpty();
        server.verify();
    }

    @Test
    void aMissingDocument_orOneOfAnotherDid_doesNotResolve() {
        server.expect(requestTo("http://identity.cxve.localhost/acme/did.json")).andRespond(withStatus(HttpStatus.NOT_FOUND));
        server.expect(requestTo("http://identity.cxve.localhost/acme/did.json"))
                .andRespond(withSuccess("""
                        {"id": "did:web:identity.cxve.localhost:other"}""", MediaType.APPLICATION_JSON));

        assertThat(resolver.problem(DID))
                .contains("no DID document could be fetched from http://identity.cxve.localhost/acme/did.json");
        assertThat(resolver.problem(DID)).hasValueSatisfying(problem -> assertThat(problem)
                .contains("declares the id 'did:web:identity.cxve.localhost:other'"));
    }

    @Test
    void aDidOfAnotherMethod_doesNotResolve() {
        assertThat(resolver.problem("did:key:z6Mkabc")).hasValueSatisfying(problem -> assertThat(problem)
                .contains("not a did:web"));
        assertThat(resolver.problem("did:web:")).hasValueSatisfying(problem -> assertThat(problem)
                .contains("names no host"));
    }
}
