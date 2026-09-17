package com.metaform.cxve.verification.adapter.out.management;

import com.metaform.cxve.verification.adapter.out.auth.TokenProvider;
import com.metaform.cxve.verification.application.TestFixtureAccess;
import com.metaform.cxve.verification.application.VerificationException;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.match.MockRestRequestMatchers;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.ExpectedCount.manyTimes;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

/**
 * How the catalog wait tells a counterparty that is not ready apart from one that will never be.
 *
 * <p>The difference matters because of what this environment is for: an address that answers
 * "nothing here" is a conformance defect in the counterparty's DID document — the only place an
 * external participant publishes where to reach it — and reporting that as a timeout would send
 * an operator looking at the wrong party for as long as the budget lasts.
 */
class ManagementApiClientTest {

    private static final String DSP = "http://sut.example.com/api/dsp/tenant-9/http-dsp-profile-2025-1";
    private static final String DID = "did:web:sut.example.com";

    /** The control plane's report of a counterparty answer, which is all the 502 body carries. */
    private static String counterPartyResponded(int status) {
        return """
                [{"message":"Counter Party responded with Response{protocol=http/1.1, code=%d, \
                message=Not Found, url=%s/catalog/request}. Body: <html>...","type":"GeneralError"}]"""
                .formatted(status, DSP);
    }

    private record Fixture(ManagementApiClient client, MockRestServiceServer server) {
    }

    private Fixture fixture() {
        var builder = RestClient.builder().baseUrl("http://cp");
        var server = MockRestServiceServer.bindTo(builder).build();
        TokenProvider tokens = (resource, scopes) -> "token";
        var client = new ManagementApiClient(builder.build(), tokens,
                TestFixtureAccess.props(Map.of()), new JsonMapper());
        return new Fixture(client, server);
    }

    @Test
    void aCounterpartyServingNoDspFailsImmediately() {
        var fixture = fixture();
        // ONE response stubbed: a second request would mean the run is waiting on an address that
        // has already given its final answer.
        fixture.server().expect(MockRestRequestMatchers.requestTo(
                        "http://cp/participants/pctx-vp/catalog/request"))
                .andRespond(withStatus(HttpStatus.BAD_GATEWAY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(counterPartyResponded(404)));

        assertThatThrownBy(() -> fixture.client()
                .awaitCatalogOffer("pctx-vp", DSP, DID, "ccm-api", Duration.ofSeconds(30)))
                .isInstanceOf(VerificationException.class)
                // names the address dialled and where it came from — the finding, not "timed out"
                .hasMessageContaining(DSP)
                .hasMessageContaining("404")
                .hasMessageContaining("ProtocolEndpoint");
        fixture.server().verify();
    }

    @Test
    void aMethodNotAllowedIsEquallyFinal() {
        // A catalog request is a POST and nothing else, so 405 says the same as 404: whatever is
        // at that address, it is not a DSP catalog endpoint.
        var fixture = fixture();
        fixture.server().expect(MockRestRequestMatchers.requestTo(
                        "http://cp/participants/pctx-vp/catalog/request"))
                .andRespond(withStatus(HttpStatus.BAD_GATEWAY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(counterPartyResponded(405)));

        assertThatThrownBy(() -> fixture.client()
                .awaitCatalogOffer("pctx-vp", DSP, DID, "ccm-api", Duration.ofSeconds(30)))
                .isInstanceOf(VerificationException.class)
                .hasMessageContaining("405");
        fixture.server().verify();
    }

    @Test
    void anErrorThatIsNotTheCounterpartysOwnAnswerKeepsRetrying() {
        // Our own control plane failing, a counterparty still starting, one refusing our
        // credentials: none of those are a verdict about the address, so the wait rides them out
        // and reports a timeout naming what it last saw.
        var fixture = fixture();
        fixture.server().expect(manyTimes(), MockRestRequestMatchers.requestTo(
                        "http://cp/participants/pctx-vp/catalog/request"))
                .andRespond(withServerError().body("controlplane is restarting"));

        assertThatThrownBy(() -> fixture.client()
                .awaitCatalogOffer("pctx-vp", DSP, DID, "ccm-api", Duration.ofMillis(300)))
                .isInstanceOf(VerificationException.class)
                .hasMessageContaining("timed out")
                .hasMessageContaining("catalog request failed with HTTP 500");
    }

    /** A catalog dataset as the management API compacts it (shape taken from a live catalog). */
    private static String dataset(String id, String subject, String version) {
        var api = subject == null ? "" : """
                "dct:type": {"@id": "https://w3id.org/catenax/taxonomy#CCMAPI"},
                "dct:subject": {"@id": "https://w3id.org/catenax/taxonomy#%s"},
                "https://w3id.org/catenax/ontology/common#version": "%s",
                """.formatted(subject, version);
        return """
                {"@id": "%s", "@type": "Dataset", %s "hasPolicy": [{"@id": "offer-%s", "@type": "Offer"}]}"""
                .formatted(id, api, id);
    }

    private static String catalog(String... datasets) {
        return """
                {"@context": ["https://w3id.org/dspace/2025/1/context.jsonld"], "@type": "Catalog",
                 "dataset": [%s]}""".formatted(String.join(",", datasets));
    }

    @Test
    void theProviderApiOfferIsFoundByWhatItDeclares_whateverTheVendorNamedIt() {
        var fixture = fixture();
        fixture.server().expect(MockRestRequestMatchers.requestTo("http://cp/participants/pctx-vp/catalog/request"))
                .andRespond(withStatus(HttpStatus.OK).contentType(MediaType.APPLICATION_JSON).body(catalog(
                        dataset("some-data", null, null),
                        dataset("vendor-inbox", "CompanyCertificateManagementConsumerApi", "3.0"),
                        dataset("acme-ccm-provider-api", "CompanyCertificateManagementProviderApi", "3.0"))));

        var offer = fixture.client().awaitCatalogOffer("pctx-vp", DSP, DID, CcmApi.provider("3.0"), Duration.ofSeconds(30));

        assertThat(offer.datasetId()).isEqualTo("acme-ccm-provider-api");
        assertThat(offer.offer().path("@id").asText()).isEqualTo("offer-acme-ccm-provider-api");
        fixture.server().verify();
    }

    @Test
    void theSameApiOfferedTwiceFailsImmediately() {
        // CX-0135 allows one asset per API and version per business partner. Picking one of two
        // would verify an arbitrary offer — the catalog's answer is final, so no second request.
        var fixture = fixture();
        fixture.server().expect(MockRestRequestMatchers.requestTo("http://cp/participants/pctx-vp/catalog/request"))
                .andRespond(withStatus(HttpStatus.OK).contentType(MediaType.APPLICATION_JSON).body(catalog(
                        dataset("ccm-a", "CompanyCertificateManagementProviderApi", "3.0"),
                        dataset("ccm-b", "CompanyCertificateManagementProviderApi", "3.0"))));

        assertThatThrownBy(() -> fixture.client()
                .awaitCatalogOffer("pctx-vp", DSP, DID, CcmApi.provider("3.0"), Duration.ofSeconds(30)))
                .isInstanceOf(VerificationException.class)
                .hasMessageContaining("ambiguous")
                .hasMessageContaining("ccm-a")
                .hasMessageContaining("ccm-b");
        fixture.server().verify();
    }

    @Test
    void whileNoDatasetDeclaresTheApi_theWaitNamesWhatTheCatalogOffers() {
        // An offer under the wrong identity looks exactly like a missing one otherwise: the run
        // would sit at "Establish pull flow" with nothing pointing at the asset that IS there.
        var fixture = fixture();
        fixture.server().expect(manyTimes(), MockRestRequestMatchers.requestTo(
                        "http://cp/participants/pctx-vp/catalog/request"))
                .andRespond(withStatus(HttpStatus.OK).contentType(MediaType.APPLICATION_JSON).body(catalog(
                        dataset("foobar-ccm-api", null, null),
                        dataset("old-ccm", "CompanyCertificateManagementProviderApi", "2.0"))));

        assertThatThrownBy(() -> fixture.client()
                .awaitCatalogOffer("pctx-vp", DSP, DID, CcmApi.provider("3.0"), Duration.ofMillis(300)))
                .isInstanceOf(VerificationException.class)
                .hasMessageContaining("timed out")
                .hasMessageContaining("foobar-ccm-api (no CX-0135 API subject)")
                .hasMessageContaining("old-ccm (CompanyCertificateManagementProviderApi 2.0)");
    }

    @Test
    void aCounterpartyRejectingOurCredentialsKeepsRetrying() {
        // 401 means it IS serving DSP and did not accept us — a different finding, and one that
        // can still come good while a presentation settles. Not this check's business.
        var fixture = fixture();
        fixture.server().expect(manyTimes(), MockRestRequestMatchers.requestTo(
                        "http://cp/participants/pctx-vp/catalog/request"))
                .andRespond(withStatus(HttpStatus.BAD_GATEWAY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(counterPartyResponded(401)));

        assertThatThrownBy(() -> fixture.client()
                .awaitCatalogOffer("pctx-vp", DSP, DID, "ccm-api", Duration.ofMillis(300)))
                .isInstanceOf(VerificationException.class)
                .hasMessageContaining("timed out");
    }
}
