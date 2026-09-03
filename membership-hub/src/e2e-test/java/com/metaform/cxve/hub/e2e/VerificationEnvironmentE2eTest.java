package com.metaform.cxve.hub.e2e;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static com.metaform.cxve.hub.e2e.TestLog.log;
import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.InstanceOfAssertFactories.LIST;
import static org.awaitility.Awaitility.await;

/**
 * End-to-end tests against a running VE (stood up by scripts/install-ve.sh) through its
 * gateway — black box, no dependency on the app modules' classes; wire payloads are mirrored
 * locally (see {@link NewParticipantData}). Participants are onboarded through the Membership
 * Hub — the full journey: CX-0006 registration via the Onboarding API, then EDC resource
 * provisioning via the CFM Tenant Manager (see {@link MembershipHubApi}); the direct OSP-facing
 * surface of the Onboarding API keeps its own contract test. The suite is not part of
 * {@code check}/{@code build}; run it with {@code ./gradlew e2eTest}.
 */
class VerificationEnvironmentE2eTest {
    public static final ManagementApi.Constraint MEMBERSHIP_CONSTRAINT = new ManagementApi.Constraint("Membership", "eq", "active");
    public static final ManagementApi.Constraint FRAMEWORK_AGREEMENT_CONSTRAINT = new ManagementApi.Constraint("FrameworkAgreement", "eq", "DataExchangeGovernance:1.0");
    public static final ManagementApi.Constraint USAGE_PURPOSE_CONSTRAINT = new ManagementApi.Constraint("UsagePurpose", "isAnyOf", "cx.pcf.base:1");
    public static final ManagementApi.Constraint DATA_USAGE_DEFINITION_CONSTRAINT = new ManagementApi.Constraint("DataUsageEndDefinition", "eq", "cx.dataUsageEnd.unlimited:1");

    private static final String ONBOARDING_API_URL = "http://cxve.localhost/onboarding";
    private static final String MEMBERSHIP_HUB_URL = "http://cxve.localhost/hub";
    // Hostname under which the in-cluster Onboarding API can reach THIS test process: the
    // callback receiver (WireMock) runs on the host. Docker Desktop (macOS/Windows) resolves
    // host.docker.internal to it from inside pods; on plain Linux Docker (e.g. a CI runner)
    // that name does NOT exist — set E2E_CALLBACK_HOST to an address the kind node routes to
    // the host (e2e.sh derives the kind bridge gateway). Plain localhost would loop back to
    // the pod.
    private static final String CALLBACK_HOST =
            System.getenv().getOrDefault("E2E_CALLBACK_HOST", "host.docker.internal");

    private final ObjectMapper objectMapper = new ObjectMapper();

    // The external registration-status consumer: WireMock listens on a dynamic port and serves
    // POST /registration/status, receiving the Onboarding API's status callbacks. The
    // ServeEventListener counts the latch down on every served /registration/status request.
    // h2c is disabled because the app's JDK HttpClient upgrades plain-http connections to
    // HTTP/2, and that path fails against Jetty with "Received RST_STREAM: Stream cancelled".
    @RegisterExtension
    static WireMockExtension wiremock = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort().http2PlainDisabled(true))
            .build();

    private static final String TOKEN_EXCHANGE_URL = "http://cxve.localhost/api/auth/token";
    private static final String MANAGEMENT_API_URL = "http://cxve.localhost/api/management/v5";
    private static final String CERTO_API_URL = "http://cxve.localhost/api/certo/management/v1";
    // Transfer type of the CCM flows — must be exactly this profile URI; it keys the
    // participant's siglet transfer-type mapping (installed at provisioning from
    // participant.ccm.* config), the dataplane registration, and the transfer request.
    private static final String CCM_TRANSFER_TYPE = "HttpData-PULL";
    // kubectl must target the VE cluster regardless of the current kubectl context
    private static final String KUBECONFIG = System.getenv().getOrDefault("KUBECONFIG",
            System.getProperty("user.home") + "/.kube/cxve.config");

    private final TokenExchange tokenExchange = new TokenExchange(TOKEN_EXCHANGE_URL, KUBECONFIG);
    private final MembershipHubApi hub = new MembershipHubApi(MEMBERSHIP_HUB_URL);

    /**
     * Bearer token of the OSP caller, obtained exactly as an external onboarding service
     * provider obtains one: OAuth2 client_credentials against the VE's OSP IdP (Ory Hydra)
     * through the gateway, with the osp-client the umbrella chart seeds. Deliberately NOT
     * jwtlet: its only grant exchanges Kubernetes SA tokens, which external parties don't have.
     * Fetched per call: tokens expire, the suite runs for minutes.
     */
    private static String ospAccessToken() {
        return given()
                .auth().preemptive().basic("osp-client", "osp-secret")
                .formParam("grant_type", "client_credentials")
                .formParam("scope", "configure_partner_registration")
                .post("http://cxve.localhost/auth/osp/oauth2/token")
                .then().statusCode(200)
                .extract().path("access_token");
    }

    /**
     * CX-0135 CCM Flow B (certo docs/FLOWS.md): provider-initiated certificate push, full B1
     * loop. Certo resolves every outbound CCM call's token + counterparty endpoint from Siglet's
     * flow cache, populated when a data transfer of the CCM transfer type reaches STARTED — and
     * only ever on the DSP-consumer side of a flow. So each side that PLACES CCM calls first
     * becomes the DSP consumer of a flow on the OTHER side's CCM asset:
     * <ul>
     *   <li>flow "push": cert-provider consumes the cert-consumer's inbox asset — its transfer
     *       id is the {@code flowId} of the publish (provider -> consumer notification);</li>
     *   <li>flow "pull": cert-consumer consumes the cert-provider's api asset — its transfer id
     *       is the {@code flowId} of retrieve + accept (consumer -> provider calls).</li>
     * </ul>
     * Each CCM asset carries the counterparty's BPN as a dataplane-metadata property: Siglet
     * stamps it into the flow token as the {@code bpn} claim certo's inbound verification
     * requires (alongside sub = caller DID, aud = receiving tenant DID).
     */
    @Test
    void certificateExchange() {
        var runId = UUID.randomUUID().toString().substring(0, 8);

        var pair = onboardProviderAndConsumer(runId);
        var provider = pair.provider();
        var consumer = pair.consumer();
        var providerPcid = provider.participantContextId();
        var consumerPcid = consumer.participantContextId();

        var mgmt = new ManagementApi(MANAGEMENT_API_URL,
                tokenExchange.getParticipantToken("seed-jobs", "issuer", "admin"));
        log("management-API token obtained (seed-jobs -> issuer, scope admin)");

        // both sides offer their CCM asset (certo's protocol API behind the CCM transfer
        // type), each stamped with the OTHER side's BPN — the asset owner's siglet
        // context mints the flow token, and its claims must describe the caller
        var providerAssetId = "ccm-api-" + runId;
        var consumerAssetId = "ccm-inbox-" + runId;
        seedCcmOffer(mgmt, providerPcid, providerAssetId, "p-" + runId);
        seedCcmOffer(mgmt, consumerPcid, consumerAssetId, "c-" + runId);

        // "pull" flow: cert-consumer -> cert-provider (used for retrieve + acceptance report)
        var flowIdPull = establishCcmFlow(mgmt, consumerPcid, providerPcid, provider.holderId(), providerAssetId);
        // "push" flow: cert-provider -> cert-consumer (used for the publish notification)
        var flowIdPush = establishCcmFlow(mgmt, providerPcid, consumerPcid, consumer.holderId(), consumerAssetId);

        // the certo SA is mapped (resource "sudo") to the certo-mgmt-api scopes by the
        // umbrella's certo-jwtlet-seed; certo validates the token itself (no clearglass)
        var certo = new CertoApi(CERTO_API_URL,
                tokenExchange.getParticipantToken("certo", "sudo", "certo-mgmt-api:write"));
        log("certo-API token obtained (certo -> sudo, scope certo-mgmt-api:write)");
        certo.awaitParticipantContext(providerPcid, Duration.ofMinutes(2));
        certo.awaitParticipantContext(consumerPcid, Duration.ofMinutes(2));

        // Flow B: backend issues the certificate (state only), then publish opens a FULFILLED
        // exchange and pushes the lifecycle CREATED event to the consumer. The document is the
        // checked-in sample PDF from the suite's resources; the later download is compared
        // against it byte-for-byte to prove a real content round-trip.
        var documentContent = certificateDocument();
        var documentId = certo.addDocument(providerPcid, "application/pdf", documentContent);
        var certificateId = certo.addCertificate(providerPcid, provider.bpn(), documentId);
        var exchangeId = certo.publish(providerPcid, certificateId, consumer.bpn(), consumer.holderId(), flowIdPush);

        // client-driven tail: retrieve pulls the certificate metadata AND the document binary
        // from the provider over the pull flow — download it and verify it byte-for-byte
        // against the upload
        var retrieved = certo.retrieve(consumerPcid, exchangeId, flowIdPull);
        assertThat(retrieved.path("certificate").path("certificateId").asText()).isEqualTo(certificateId);
        assertThat(retrieved.path("documents").size()).isEqualTo(1);
        var document = retrieved.path("documents").path(0);
        assertThat(document.path("documentId").asText()).isEqualTo(documentId);
        assertThat(document.path("mediaType").asText()).isEqualTo("application/pdf");
        var downloaded = Base64.getDecoder().decode(document.path("contentBase64").asText());
        assertThat(downloaded)
                .withFailMessage("downloaded document differs from the uploaded one")
                .isEqualTo(documentContent);
        var downloadPath = downloadDocument("certificate-document-" + runId + ".pdf", downloaded);
        log("document downloaded to %s (%d bytes, content verified)", downloadPath, downloaded.length);

        // the acceptance report to the provider is best-effort (post-commit), so drive the
        // verdict until the PROVIDER's recorded view shows it: re-driving accept with the same
        // verdict is certo's documented recovery for a lost report (no state change, re-report)
        await().atMost(Duration.ofMinutes(2)).pollInterval(Duration.ofSeconds(5)).untilAsserted(() -> {
            certo.accept(consumerPcid, exchangeId, "ACCEPTED", flowIdPull);
            var exchange = certo.getExchange(providerPcid, exchangeId);
            assertThat(exchange.path("fulfillmentStatus").asText()).isEqualTo("FULFILLED");
            assertThat(exchange.path("acceptanceStatus").asText()).isEqualTo("ACCEPTED");
        });
        log("certificate exchange %s closed: FULFILLED / ACCEPTED", exchangeId);
    }

    /**
     * The consumer-INITIATED pull, the mirror of {@link #certificateExchange()}'s provider-initiated
     * push.
     *
     * <p>The distinction matters beyond coverage. A publish constructs its exchange already
     * {@code FULFILLED}, so it never holds the early Fulfillment states and never reports them. Only
     * this flow walks the CX-0135 §2.1.3 state machine from the start: the consumer asks for a
     * certificate the provider does not yet hold, the exchange waits with <b>no certificate identity
     * at all</b>, and a later fulfillment binds one. That is also the only flow in which certo emits
     * {@code events.certificate.exchange.certificationRequested}.
     *
     * <p>Both directions are exercised: the pull flow carries the consumer's request and its later
     * retrieve/verdict, the push flow carries the provider's fulfillment notification.
     */
    @Test
    void consumerInitiatedCertificateRequest() {
        var runId = UUID.randomUUID().toString().substring(0, 8);

        var pair = onboardProviderAndConsumer(runId);
        var provider = pair.provider();
        var consumer = pair.consumer();
        var providerPcid = provider.participantContextId();
        var consumerPcid = consumer.participantContextId();

        var mgmt = new ManagementApi(MANAGEMENT_API_URL,
                tokenExchange.getParticipantToken("seed-jobs", "issuer", "admin"));
        log("management-API token obtained (seed-jobs -> issuer, scope admin)");

        var providerAssetId = "ccm-api-" + runId;
        var consumerAssetId = "ccm-inbox-" + runId;
        seedCcmOffer(mgmt, providerPcid, providerAssetId, "p-" + runId);
        seedCcmOffer(mgmt, consumerPcid, consumerAssetId, "c-" + runId);

        // pull: cert-consumer -> cert-provider (the request itself, then retrieve + verdict)
        var flowIdPull = establishCcmFlow(mgmt, consumerPcid, providerPcid, provider.holderId(), providerAssetId);
        // push: cert-provider -> cert-consumer (the fulfillment notification)
        var flowIdPush = establishCcmFlow(mgmt, providerPcid, consumerPcid, consumer.holderId(), consumerAssetId);

        var certo = new CertoApi(CERTO_API_URL,
                tokenExchange.getParticipantToken("certo", "sudo", "certo-mgmt-api:write"));
        log("certo-API token obtained (certo -> sudo, scope certo-mgmt-api:write)");
        certo.awaitParticipantContext(providerPcid, Duration.ofMinutes(2));
        certo.awaitParticipantContext(consumerPcid, Duration.ofMinutes(2));

        // 1. the consumer asks for a certificate the provider does not hold yet
        var certificateType = "ISO9001";
        var site = provider.bpn();
        var exchangeId = certo.initiateRequest(consumerPcid, provider.bpn(), provider.holderId(),
                certificateType, List.of(site), flowIdPull);

        // the provider's record exists and is waiting, with NO certificate bound — the property that
        // distinguishes a pull from a publish
        var pending = certo.getExchange(providerPcid, exchangeId);
        assertThat(pending.path("fulfillmentStatus").asText()).isIn("REQUESTED", "CERTIFICATION_REQUESTED");
        assertThat(pending.path("certificateId").isNull() || pending.path("certificateId").asText().isEmpty())
                .withFailMessage("a request the provider cannot yet satisfy must carry no certificate: %s", pending)
                .isTrue();
        log("request %s open on the provider, awaiting issuance (%s)",
                exchangeId, pending.path("fulfillmentStatus").asText());

        // 2. the backend issues the certificate, which now covers the waiting request
        var documentContent = certificateDocument();
        var documentId = certo.addDocument(providerPcid, "application/pdf", documentContent);
        var certificateId = certo.addCertificate(providerPcid, provider.bpn(), documentId);

        await().atMost(Duration.ofMinutes(2)).pollInterval(Duration.ofSeconds(5)).untilAsserted(() -> {
            var fulfillable = certo.fulfillableRequests(providerPcid, certificateId);
            assertThat(fulfillable.path("items").findValuesAsText("exchangeId"))
                    .withFailMessage("the issued certificate should satisfy the waiting request: %s", fulfillable)
                    .contains(exchangeId);
        });

        // 3. the provider fulfills it, binding the certificate identity and pushing FULFILLED
        certo.fulfillRequest(providerPcid, exchangeId, flowIdPush);

        // 4. the consumer mirrors the provider's status. The push may already have delivered it, so
        // poll until FULFILLED rather than assuming which path won — polling is idempotent and
        // records nothing when the status has not moved.
        await().atMost(Duration.ofMinutes(2)).pollInterval(Duration.ofSeconds(5)).untilAsserted(() -> {
            var mirrored = certo.pollRequest(consumerPcid, exchangeId, flowIdPull);
            assertThat(mirrored.path("fulfillmentStatus").asText()).isEqualTo("FULFILLED");
            assertThat(mirrored.path("certificateId").asText())
                    .withFailMessage("fulfillment must disclose the certificate identity: %s", mirrored)
                    .isEqualTo(certificateId);
        });
        log("consumer mirrored FULFILLED for exchange %s (certificate %s)", exchangeId, certificateId);

        // 5. same client-driven tail as the push flow: retrieve the content, then a terminal verdict
        var retrieved = certo.retrieve(consumerPcid, exchangeId, flowIdPull);
        assertThat(retrieved.path("certificate").path("certificateId").asText()).isEqualTo(certificateId);
        var downloaded = Base64.getDecoder().decode(retrieved.path("documents").path(0).path("contentBase64").asText());
        assertThat(downloaded)
                .withFailMessage("downloaded document differs from the uploaded one")
                .isEqualTo(documentContent);

        await().atMost(Duration.ofMinutes(2)).pollInterval(Duration.ofSeconds(5)).untilAsserted(() -> {
            certo.accept(consumerPcid, exchangeId, "ACCEPTED", flowIdPull);
            var exchange = certo.getExchange(providerPcid, exchangeId);
            assertThat(exchange.path("fulfillmentStatus").asText()).isEqualTo("FULFILLED");
            assertThat(exchange.path("acceptanceStatus").asText()).isEqualTo("ACCEPTED");
        });
        log("consumer-initiated exchange %s closed: FULFILLED / ACCEPTED", exchangeId);
    }

    /**
     * CX-0135 <b>v2.4.0</b> legacy bridge (certo docs/FLOWS.md Flow F): the provider-initiated
     * <b>embedded</b> push — the v2.4.0 counterpart of {@link #certificateExchange()}'s content round-trip.
     * Everything else in this suite drives certo's native v3.0.0 CloudEvents protocol; here the publish names
     * {@code protocolVersion 2.4.0}, so the provider renders a {@code BusinessPartnerCertificate} 3.1.0 and
     * POSTs it to the consumer's {@code /companycertificate/push} over the same DSP flow and siglet-minted
     * flow token. Embedded is the point: a by-reference v2.4.0 push ({@code /companycertificate/available})
     * is acknowledged only — the content would have to come over the per-asset EDC pull, which is out of
     * scope (certo docs/PROBLEM_NOTES.md §4.1/§4.2) — so an embedded push is the only v2.4.0 path that moves
     * a document at all.
     *
     * <p>Two identities are consumer-LOCAL here, and the assertions pin exactly that: v2.4.0 has no exchange
     * concept, so the receiving side mints a surrogate {@code exchangeId} the pushing side never learns; and
     * a 3.1.0 certificate carries no {@code certificateId}, so the receiving side derives one from
     * {@code issuerBpn|registrationNumber} (§5.3). The consumer is therefore found through its reconciliation
     * query, not by the provider's exchange id.
     *
     * <p>Consequently the acceptance verdict is asserted on the CONSUMER only. The v2.4.0 report
     * ({@code /companycertificate/status}) correlates by {@code (documentId = certificateId, verified DID)},
     * and the two sides hold different certificate ids for the same artifact, so in a certo-to-certo
     * deployment that report cannot bind to the provider's exchange (§4.3). {@code accept} is best-effort and
     * post-commit, so it still succeeds; the provider's FULFILLED exchange is asserted, its acceptance
     * deliberately is not.
     */
    @Test
    void legacyV240EmbeddedPush() {
        var runId = UUID.randomUUID().toString().substring(0, 8);

        var pair = onboardProviderAndConsumer(runId);
        var provider = pair.provider();
        var consumer = pair.consumer();
        var providerPcid = provider.participantContextId();
        var consumerPcid = consumer.participantContextId();

        var mgmt = new ManagementApi(MANAGEMENT_API_URL,
                tokenExchange.getParticipantToken("seed-jobs", "issuer", "admin"));
        log("management-API token obtained (seed-jobs -> issuer, scope admin)");

        // the v2.4.0 endpoints live under the same certo root as the v3 ones, so the CCM offers and flows
        // are seeded exactly as for a native exchange — only the publish body differs
        var providerAssetId = "ccm-api-" + runId;
        var consumerAssetId = "ccm-inbox-" + runId;
        seedCcmOffer(mgmt, providerPcid, providerAssetId, "p-" + runId);
        seedCcmOffer(mgmt, consumerPcid, consumerAssetId, "c-" + runId);

        // pull: cert-consumer -> cert-provider (carries the acceptance report)
        var flowIdPull = establishCcmFlow(mgmt, consumerPcid, providerPcid, provider.holderId(), providerAssetId);
        // push: cert-provider -> cert-consumer (carries the /companycertificate/push)
        var flowIdPush = establishCcmFlow(mgmt, providerPcid, consumerPcid, consumer.holderId(), consumerAssetId);

        var certo = new CertoApi(CERTO_API_URL,
                tokenExchange.getParticipantToken("certo", "sudo", "certo-mgmt-api:write"));
        log("certo-API token obtained (certo -> sudo, scope certo-mgmt-api:write)");
        certo.awaitParticipantContext(providerPcid, Duration.ofMinutes(2));
        certo.awaitParticipantContext(consumerPcid, Duration.ofMinutes(2));

        // the backend issues the certificate; the registration number is run-scoped because it is half of
        // the identity the receiving side derives the certificateId from
        var documentContent = certificateDocument();
        var documentId = certo.addDocument(providerPcid, "application/pdf", documentContent);
        var registrationNumber = "CXVE-E2E-" + runId;
        var certificateId = certo.addCertificate(providerPcid, provider.bpn(), documentId, registrationNumber);

        var exchangeId = certo.publish(providerPcid, certificateId, consumer.bpn(), consumer.holderId(),
                flowIdPush, "2.4.0", true);
        log("v2.4.0 embedded push delivered (provider exchange %s)", exchangeId);

        // the consumer's record of the push: nothing on the wire identified it, so it is found through the
        // reconciliation query — one fresh tenant, one delivery, one item
        var pushed = new AtomicReference<JsonNode>();
        await().atMost(Duration.ofMinutes(1)).pollInterval(Duration.ofSeconds(2)).untilAsserted(() -> {
            var items = certo.consumerExchanges(consumerPcid, true).path("items");
            assertThat(items.size())
                    .withFailMessage("expected exactly one exchange awaiting acceptance on the consumer: %s", items)
                    .isEqualTo(1);
            pushed.set(items.path(0));
        });
        var consumerExchangeId = pushed.get().path("exchangeId").asText();
        assertThat(consumerExchangeId)
                .withFailMessage("v2.4.0 assigns no exchangeId, so the consumer must hold a surrogate of its "
                                 + "own — not the provider's %s", exchangeId)
                .isNotEqualTo(exchangeId);
        assertThat(pushed.get().path("embedded").asBoolean())
                .withFailMessage("an embedded push must leave the content inline on the consumer: %s", pushed.get())
                .isTrue();
        assertThat(pushed.get().path("fulfillmentStatus").asText()).isEqualTo("FULFILLED");
        assertThat(pushed.get().path("certificateId").asText())
                .withFailMessage("a 3.1.0 certificate carries no id, so the consumer must derive its own — "
                                 + "not reuse the provider's %s", certificateId)
                .isNotEqualTo(certificateId);
        log("consumer recorded the pushed certificate: surrogate exchange %s, derived certificate %s",
                consumerExchangeId, pushed.get().path("certificateId").asText());

        // retrieve WITHOUT a flow: the content came with the push, so nothing is pulled back
        var retrieved = certo.retrieveEmbedded(consumerPcid, consumerExchangeId);
        assertThat(retrieved.path("certificate").path("certificateType").asText()).isEqualTo("ISO9001");
        assertThat(retrieved.path("certificate").path("registrationNumber").asText())
                .withFailMessage("the up-converted certificate must carry the issued registration number: %s",
                        retrieved.path("certificate"))
                .isEqualTo(registrationNumber);
        assertThat(retrieved.path("documents").size()).isEqualTo(1);
        var document = retrieved.path("documents").path(0);
        assertThat(document.path("documentId").asText()).isEqualTo(documentId);
        assertThat(document.path("mediaType").asText()).isEqualTo("application/pdf");
        var downloaded = Base64.getDecoder().decode(document.path("contentBase64").asText());
        assertThat(downloaded)
                .withFailMessage("the inlined document differs from the uploaded one")
                .isEqualTo(documentContent);
        var downloadPath = downloadDocument("certificate-document-v240-" + runId + ".pdf", downloaded);
        log("document downloaded to %s (%d bytes, content verified)", downloadPath, downloaded.length);

        // terminal verdict, rendered as a v2.4.0 /companycertificate/status over the pull flow. Only the
        // consumer's own record is asserted — see the javadoc on why the provider cannot bind that report.
        certo.accept(consumerPcid, consumerExchangeId, "ACCEPTED", flowIdPull);
        await().atMost(Duration.ofMinutes(1)).pollInterval(Duration.ofSeconds(2)).untilAsserted(() -> {
            var recorded = findExchange(certo.consumerExchanges(consumerPcid, false), consumerExchangeId);
            assertThat(recorded.path("acceptanceStatus").asText()).isEqualTo("ACCEPTED");
        });
        var providerExchange = certo.getExchange(providerPcid, exchangeId);
        assertThat(providerExchange.path("fulfillmentStatus").asText()).isEqualTo("FULFILLED");
        log("v2.4.0 exchange closed: provider %s FULFILLED, consumer %s ACCEPTED", exchangeId, consumerExchangeId);
    }

    /** One exchange out of a consumer exchange page, by id; fails the assertion if the page has no such row. */
    private static JsonNode findExchange(JsonNode page, String exchangeId) {
        for (var item : page.path("items")) {
            if (exchangeId.equals(item.path("exchangeId").asText())) {
                return item;
            }
        }
        throw new AssertionError("exchange %s not in the consumer's exchange page: %s".formatted(exchangeId, page));
    }

    /**
     * The regression test for the OSP-facing surface of the Onboarding API itself, exercised the
     * way an external onboarding service provider uses it: register a status callback (the one
     * call gated on the {@code configure_partner_registration} scope), submit a registration, and
     * receive the CONFIRMED callback carrying the caller-supplied externalId. This path covers
     * the registration leg only — no EDC resources are provisioned (that is the Membership Hub's
     * job, exercised by the certificate-exchange tests).
     */
    @Test
    void ospRegistrationStatusContract() {
        wiremock.stubFor(post(urlPathEqualTo("/registration/status"))
                .willReturn(okJson("{}")));

        // set callback url: status updates land on the WireMock stub above
        var callbackUrl = "http://%s:%d/registration/status".formatted(CALLBACK_HOST, wiremock.getPort());
        given()
                .baseUri(ONBOARDING_API_URL)
                .header("Authorization", "Bearer " + ospAccessToken())
                .contentType(ContentType.JSON)
                .body(new SetCallbackRequest(callbackUrl, null, null, null))
                .post("/api/administration/registrationstatus/callback")
                .then().statusCode(204);

        var externalId = "osp-contract-" + UUID.randomUUID().toString().substring(0, 8);
        submitOspRegistration("OSP Contract Corp " + externalId, "ospcontract-" + externalId.substring(13), externalId);

        log("waiting for the CONFIRMED status callback (externalId=%s)...", externalId);
        await().atMost(Duration.ofMinutes(2)).pollInterval(Duration.ofSeconds(2)).untilAsserted(() -> {
            var byExternalId = callbackResults();
            assertThat(byExternalId).containsKey(externalId);
            // pin the status: a REJECTED callback must fail here, not as an opaque mismatch later
            assertThat(byExternalId.get(externalId).status())
                    .withFailMessage("registration not confirmed: %s", byExternalId.get(externalId))
                    .isEqualTo("CONFIRMED");
        });
        log("CONFIRMED callback received for %s", externalId);
    }

    /**
     * Onboards a provider + consumer pair through the Membership Hub and waits until both are
     * PROVISIONED; the returned {@link OnboardingResult}s carry the provisioned identities
     * straight from the hub's correlated record — participant context id included, so nothing
     * needs to be recovered from the Tenant Manager here.
     */
    private OnboardedPair onboardProviderAndConsumer(String runId) {
        var provider = onboardMember("Provider " + runId, "provider-" + runId);
        var consumer = onboardMember("Consumer " + runId, "consumer-" + runId);
        log("provider onboarded: pcid=%s did=%s bpn=%s", provider.participantContextId(), provider.holderId(), provider.bpn());
        log("consumer onboarded: pcid=%s did=%s bpn=%s", consumer.participantContextId(), consumer.holderId(), consumer.bpn());
        return new OnboardedPair(provider, consumer);
    }

    private OnboardingResult onboardMember(String name, String shortName) {
        // the BPN is required by the hub's API; derived deterministically from the run-unique
        // short name, like the VAT id (duplicates of active registrations are rejected)
        var bpn = bpnFor(shortName);
        var submitted = hub.onboard(name, shortName, bpn, "DE" + String.format("%08X", Math.abs(shortName.hashCode())));
        var externalId = submitted.path("externalId").asText();
        var provisioned = hub.awaitProvisioned(externalId, Duration.ofMinutes(10));
        var pcid = provisioned.path("participantContextId").asText();
        // Guard against wire-contract skew: a PROVISIONED membership always carries the context
        // id, so an empty value means the deployed hub serves a different field name than this
        // suite mirrors — fail here, not as an opaque 4xx three steps later.
        assertThat(pcid)
                .withFailMessage("PROVISIONED membership %s carries no participantContextId — "
                        + "is the deployed hub older than this suite? Response: %s", externalId, provisioned)
                .isNotBlank();
        return new OnboardingResult(externalId, bpn, provisioned.path("did").asText(), pcid);
    }

    private record OnboardedPair(OnboardingResult provider, OnboardingResult consumer) {
    }

    /**
     * Seeds one side's CCM offer: the asset fronting certo's protocol API (with the
     * counterparty's BPN as a dataplane-metadata property, stamped into flow tokens as the
     * {@code bpn} claim), plus access/contract policies requiring all three credentials and the
     * contract definition. Ids are run-scoped; policy/definition ids may repeat across the two
     * participant contexts (resources are context-scoped).
     */
    private void seedCcmOffer(ManagementApi mgmt, String pcid, String assetId, String uniqueId) {
        var accessPolicyId = "e2e-ccm-access-policy-" + uniqueId;
        var contractPolicyId = "e2e-ccm-contract-policy-" + uniqueId;

        mgmt.createAsset(pcid, assetId, "http://cx-ve-certo.edc-v.svc.cluster.local:8080", Map.of());
        mgmt.createPolicy(pcid, accessPolicyId, "access", List.of(MEMBERSHIP_CONSTRAINT));
        mgmt.createPolicy(pcid, contractPolicyId, "use",List.of(FRAMEWORK_AGREEMENT_CONSTRAINT, USAGE_PURPOSE_CONSTRAINT, DATA_USAGE_DEFINITION_CONSTRAINT));
        mgmt.createContractDefinition(pcid, "e2e-ccm-cd-" + uniqueId, accessPolicyId, contractPolicyId);
    }

    /**
     * Catalog -> negotiation -> transfer of the CCM transfer type, as {@code consumerPcid}
     * against {@code providerPcid}'s asset. Returns the CONSUMER-side transfer process id once
     * STARTED — the id under which Siglet cached the flow token, i.e. the {@code flowId} certo
     * management calls placed BY that consumer side must carry.
     */
    private String establishCcmFlow(ManagementApi mgmt, String consumerPcid, String providerPcid,
                                    String providerDid, String assetId) {
        var providerDsp = "http://cxve.localhost/api/dsp/%s/cx-neptune".formatted(providerPcid);
        var offer = mgmt.awaitCatalogOffer(consumerPcid, providerDsp, providerDid, assetId, Duration.ofMinutes(2));
        var negotiationId = mgmt.startNegotiation(consumerPcid, providerDsp, providerDid, assetId, offer);
        var negotiation = mgmt.awaitState(
                "/participants/%s/contractnegotiations/%s".formatted(consumerPcid, negotiationId),
                Duration.ofMinutes(3), Set.of("FINALIZED"));
        var agreementId = negotiation.path("contractAgreementId").asText();
        assertThat(agreementId).isNotEmpty();
        var transferId = mgmt.startTransfer(consumerPcid, agreementId, providerDsp, CCM_TRANSFER_TYPE);
        mgmt.awaitState("/participants/%s/transferprocesses/%s".formatted(consumerPcid, transferId),
                Duration.ofMinutes(3), Set.of("STARTED"));
        log("CCM flow established: %s -> %s (asset '%s', flowId %s)", consumerPcid, providerPcid, assetId, transferId);
        return transferId;
    }

    /** The sample certificate document from the suite's resources (a small single-page PDF). */
    private static byte[] certificateDocument() {
        try (var stream = VerificationEnvironmentE2eTest.class.getResourceAsStream("/certificate-document.pdf")) {
            assertThat(stream).withFailMessage("certificate-document.pdf missing from e2e-test resources").isNotNull();
            return stream.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Writes a retrieved document binary under {@code build/e2e-downloads} (the suite runs with
     * the module directory as working directory) and returns the path — the tangible "download"
     * artifact of the certificate exchange, inspectable after the run.
     */
    private static Path downloadDocument(String fileName, byte[] content) {
        try {
            var path = Path.of("build", "e2e-downloads", fileName);
            Files.createDirectories(path.getParent());
            Files.write(path, content);
            return path.toAbsolutePath();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** All registration-status callbacks recorded by WireMock, keyed by externalId. */
    private Map<String, RegistrationStatusCallback> callbackResults() {
        return wiremock.findAll(postRequestedFor(urlPathEqualTo("/registration/status"))).stream()
                .map(request -> {
                    try {
                        return objectMapper.readValue(request.getBody(), RegistrationStatusCallback.class);
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                })
                .filter(result -> result.externalId() != null)
                .collect(Collectors.toMap(RegistrationStatusCallback::externalId, result -> result, (first, second) -> second));
    }

    /**
     * The BPN a registration is submitted under, derived deterministically from a run-unique
     * seed — so the suite can restate it later without any response having to echo it.
     */
    private static String bpnFor(String seed) {
        return ("BPNL" + String.format("%08X", Math.abs(seed.hashCode())) + "000000").substring(0, 16);
    }

    /** Submits a registration DIRECTLY to the Onboarding API, in the OSP role (osp-client). */
    private static void submitOspRegistration(String name, String shortName, String runId) {
        // bpn is a required field of the registration payload (see bpnFor)
        var bpn = bpnFor(runId);
        var newParticipant = NewParticipantData.builder()
                .name(name)
                .shortName(shortName)
                .bpn(bpn)
                .externalId(runId)
                .city("Munich")
                .streetName("Otto-Hahn-Ring")
                .streetNumber("6")
                .zipCode("81739")
                .region("BY")
                .countryAlpha2Code("DE")
                .uniqueId("VAT_ID", "DE" + runId)
                .companyRole("ACTIVE_PARTICIPANT")
                .agreement("Catena-X", "ACTIVE")
                .autoSubmit(true)
                .build();

        // kick off the registration; the caller observes the outcome via the status callback
        given()
                .baseUri(VerificationEnvironmentE2eTest.ONBOARDING_API_URL)
                .header("Authorization", "Bearer " + ospAccessToken())
                .contentType("application/json")
                .body(newParticipant)
                .post("/api/administration/registration/network/partnerregistration")
                .then()
                .statusCode(200);
        log("onboarding submitted: \"%s\" (shortName=%s, externalId=%s)", name, shortName, runId);
    }
}
