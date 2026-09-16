package com.metaform.cxve.verification.adapter.out.did;

import com.metaform.cxve.verification.application.VerificationException;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The did:web → URL rule, pinned: a wrong URL would report a healthy participant as unreachable,
 * which in a verification environment is a finding against the wrong party.
 */
class DidWebResolverTest {

    private final DidWebResolver resolver = new DidWebResolver(new JsonMapper(), "http");

    @Test
    void aBareAuthorityResolvesToTheWellKnownDocument() {
        assertThat(resolver.documentUrl("did:web:sut.example.com"))
                .isEqualTo("http://sut.example.com/.well-known/did.json");
    }

    @Test
    void pathSegmentsBecomePathElements() {
        assertThat(resolver.documentUrl("did:web:identity.cxve.localhost:tenant-9"))
                .isEqualTo("http://identity.cxve.localhost/tenant-9/did.json");
        assertThat(resolver.documentUrl("did:web:example.com:a:b:c"))
                .isEqualTo("http://example.com/a/b/c/did.json");
    }

    @Test
    void aPortIsPercentEncodedInTheAuthority() {
        // The colon is the DID's own separator, so a non-default port can only arrive encoded.
        assertThat(resolver.documentUrl("did:web:sut.example.com%3A8080:tenant-9"))
                .isEqualTo("http://sut.example.com:8080/tenant-9/did.json");
    }

    @Test
    void theSchemeFollowsTheConfiguredOne() {
        var https = new DidWebResolver(new JsonMapper(), "https");
        assertThat(https.documentUrl("did:web:sut.example.com"))
                .isEqualTo("https://sut.example.com/.well-known/did.json");
    }

    @Test
    void anIdentifierThatIsNotADidWebIsRejected() {
        assertThatThrownBy(() -> resolver.documentUrl("did:key:z6Mk"))
                .isInstanceOf(VerificationException.class)
                .hasMessageContaining("did:web");
        assertThatThrownBy(() -> resolver.documentUrl("did:web:"))
                .isInstanceOf(VerificationException.class)
                .hasMessageContaining("no authority");
        assertThatThrownBy(() -> resolver.documentUrl(null))
                .isInstanceOf(VerificationException.class);
    }
}
