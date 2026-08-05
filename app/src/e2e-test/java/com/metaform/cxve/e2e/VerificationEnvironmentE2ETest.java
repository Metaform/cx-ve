package com.metaform.cxve.e2e;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.extension.Parameters;
import com.github.tomakehurst.wiremock.extension.ServeEventListener;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.github.tomakehurst.wiremock.stubbing.ServeEvent;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
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
import static com.metaform.cxve.e2e.TestLog.log;
import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * End-to-end tests against a running VE (stood up by scripts/install-ve.sh) through its
 * gateway — black box, no dependency on the app module's classes; wire payloads are mirrored
 * locally (see {@link NewParticipantData}). The suite is not part of {@code check}/{@code build};
 * run it with {@code ./gradlew :app:e2eTest}.
 */
class VerificationEnvironmentE2ETest {
    private static final String ONBOARDING_API_URL = "http://cxve.localhost/onboarding";
    // Hostname under which the in-cluster Onboarding API can reach THIS test process: the
    // callback receiver (WireMock) runs on the host, and Docker Desktop/kind resolve
    // host.docker.internal to it from inside pods. Plain localhost would loop back to the pod.
    private static final String CALLBACK_HOST = "host.docker.internal";

    private static final long STATUS_CALLBACK_TIMEOUT_MINUTES = 1;

    // Fresh latch per test (see resetLatch); the listener below always counts down the current
    // one. Held in an AtomicReference because the listener is registered once, statically.
    private static final AtomicReference<byte[]> lastRequestPayload = new AtomicReference<>();
    private final ObjectMapper objectMapper = new ObjectMapper();

    // The external registration-status consumer: WireMock listens on a dynamic port and serves
    // POST /registration/status, receiving the Onboarding API's status callbacks. The
    // ServeEventListener counts the latch down on every served /registration/status request.
    // h2c is disabled because the app's JDK HttpClient upgrades plain-http connections to
    // HTTP/2, and that path fails against Jetty with "Received RST_STREAM: Stream cancelled".
    @RegisterExtension
    static WireMockExtension wiremock = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort().http2PlainDisabled(true).extensions(new ServeEventListener() {
                @Override
                public String getName() {
                    return "registration-status-latch";
                }

                @Override
                public void afterComplete(ServeEvent serveEvent, Parameters parameters) {
                    if (serveEvent.getRequest().getUrl().startsWith("/registration/status")) {
                        lastRequestPayload.set(serveEvent.getRequest().getBody());
                    }
                }
            }))
            .build();

    private static final String TOKEN_EXCHANGE_URL = "http://cxve.localhost/api/auth/token";
    private static final String MANAGEMENT_API_URL = "http://cxve.localhost/api/management/v5beta";
    // kubectl must target the VE cluster regardless of the current kubectl context
    private static final String KUBECONFIG = System.getenv().getOrDefault("KUBECONFIG",
            System.getProperty("user.home") + "/.kube/cxve.config");

    // e2e-owned CEL expressions: policy constraints reference these leftOperand IRIs, decoupled
    // from the catenax-profile-seeded expressions (different IRIs -> no interference, and no
    // action binding, so the same constraints work in access and contract policies).
    // MUST be absolute IRIs: the policy validator matches a constraint's leftOperand AFTER
    // JSON-LD expansion against the CEL expression's registered string — a bare name like
    // "MembershipCredential" expands under the default vocab and no longer matches, failing
    // policy creation with "leftOperand ... is not bound to any scopes/functions". Absolute
    // IRIs pass through expansion unchanged (same reason the catenax-profile seeds use IRIs).
    private static final String MEMBERSHIP_OPERAND = "https://w3id.org/cxve/e2e/policy/MembershipCredential";
    private static final String BPN_OPERAND = "https://w3id.org/cxve/e2e/policy/BpnCredential";
    private static final String GOV_OPERAND = "https://w3id.org/cxve/e2e/policy/DataExchangeGovernanceCredential";

    private final TokenExchange tokenExchange = new TokenExchange(TOKEN_EXCHANGE_URL, KUBECONFIG);

    @BeforeEach
    void stubRegistrationStatus() {
        wiremock.stubFor(post(urlPathEqualTo("/registration/status"))
                .willReturn(okJson("{}")));

        // set callback url: status updates land on the WireMock stub above
        var callbackUrl = "http://%s:%d/registration/status".formatted(CALLBACK_HOST, wiremock.getPort());
        given()
                .baseUri(ONBOARDING_API_URL)
                .contentType(ContentType.JSON)
                .body(new SetCallbackRequest(callbackUrl, null, null, null))
                .post("/api/administration/RegistrationStatus/callback")
                .then().statusCode(204);
    }

    @Test
    void onboardParticipant() throws IOException {
        var runId = UUID.randomUUID().toString();
        var name = "Test Participant " + runId;
        var shortName = "test-participant-" + runId;
        // kick off the onboarding
        onboardParticipant(name, shortName, runId);

        await().atMost(Duration.ofMinutes(1))
                .untilAsserted(() -> assertThat(lastRequestPayload.get()).isNotNull());

        var res = objectMapper.readValue(lastRequestPayload.get(), OnboardingResult.class);
        assertThat(res.participantContextId()).isNotNull();
        assertThat(res.failureReason()).isNull();
        assertThat(res.holderId()).contains("did:web:identity.cxve.localhost:" + shortName);
    }

    @Test
    void dataExchange() {
        var runId = UUID.randomUUID().toString().substring(0, 8);
        var providerExtId = "provider-" + runId;
        var consumerExtId = "consumer-" + runId;

        // onboard both participants; each completion posts an OnboardingResult callback that
        // lands in the WireMock request journal
        onboardParticipant("Provider " + runId, "provider-" + runId, providerExtId);
        onboardParticipant("Consumer " + runId, "consumer-" + runId, consumerExtId);

        log("waiting for both onboardings to complete (callbacks on /registration/status)...");
        var results = new AtomicReference<Map<String, OnboardingResult>>();
        await().atMost(Duration.ofMinutes(10)).pollInterval(Duration.ofSeconds(5)).untilAsserted(() -> {
            var byExternalId = callbackResults();
            assertThat(byExternalId.keySet()).contains(providerExtId, consumerExtId);
            results.set(byExternalId);
        });
        var provider = results.get().get(providerExtId);
        var consumer = results.get().get(consumerExtId);
        assertThat(provider.failureReason()).isNull();
        assertThat(consumer.failureReason()).isNull();
        assertThat(provider.participantContextId()).isNotNull();
        assertThat(consumer.participantContextId()).isNotNull();
        log("provider onboarded: pcid=%s did=%s bpn=%s", provider.participantContextId(), provider.holderId(), provider.bpn());
        log("consumer onboarded: pcid=%s did=%s bpn=%s", consumer.participantContextId(), consumer.holderId(), consumer.bpn());

        // the seed-jobs SA is mapped to resource "issuer" with role scope admin ->
        // management-api:admin, which authorizes operating on any participant context
        var mgmt = new ManagementApi(MANAGEMENT_API_URL,
                tokenExchange.getParticipantToken("seed-jobs", "issuer", "admin"));
        log("management-API token obtained (seed-jobs -> issuer, scope admin)");

        // CEL expressions backing the three credential constraints (runtime-level, idempotent)
        mgmt.upsertCelExpression("cxve-e2e-membership", MEMBERSHIP_OPERAND,
                "ctx.agent.claims.vc.withType('MembershipCredential').hasClaim('memberOf', 'Catena-X')");
        mgmt.upsertCelExpression("cxve-e2e-bpn", BPN_OPERAND,
                "ctx.agent.claims.vc.filter(c, c.type.exists(t, t == 'BpnCredential')).exists(c, c.credentialSubject.exists(cs, cs.bpn != null))");
        mgmt.upsertCelExpression("cxve-e2e-gov", GOV_OPERAND,
                "ctx.agent.claims.vc.withType('DataExchangeGovernanceCredential').hasClaim('contractVersion', '1.0.0')");

        // provider side: asset, access + contract policy (each requiring all three verifiable
        // credentials), contract definition. Ids are run-scoped to avoid cross-run 409s.
        var providerPcid = provider.participantContextId();
        var assetId = "e2e-asset-" + runId;
        var accessPolicyId = "e2e-access-policy-" + runId;
        var contractPolicyId = "e2e-contract-policy-" + runId;
        var allThreeCredentials = Set.of(MEMBERSHIP_OPERAND, BPN_OPERAND, GOV_OPERAND);
        mgmt.createAsset(providerPcid, assetId, "https://jsonplaceholder.typicode.com/todos/1");
        mgmt.createPolicy(providerPcid, accessPolicyId, allThreeCredentials);
        mgmt.createPolicy(providerPcid, contractPolicyId, allThreeCredentials);
        mgmt.createContractDefinition(providerPcid, "e2e-cd-" + runId, accessPolicyId, contractPolicyId);

        // consumer side: catalog -> negotiation -> transfer, all through the consumer's
        // management API; the controlplane speaks DSP to the provider's gateway address
        var consumerPcid = consumer.participantContextId();
        var providerDsp = "http://cxve.localhost/api/dsp/%s/cx-neptune".formatted(providerPcid);
        log("provider DSP endpoint: %s", providerDsp);
        var offer = mgmt.awaitCatalogOffer(consumerPcid, providerDsp, provider.holderId(), assetId,
                Duration.ofMinutes(2));

        var negotiationId = mgmt.startNegotiation(consumerPcid, providerDsp, provider.holderId(), assetId, offer);
        var negotiation = mgmt.awaitState(
                "/participants/%s/contractnegotiations/%s".formatted(consumerPcid, negotiationId),
                Duration.ofMinutes(3), Set.of("FINALIZED"));
        var agreementId = negotiation.path("contractAgreementId").asText();
        assertThat(agreementId).isNotEmpty();
        log("contract agreement reached: %s", agreementId);

        var transferId = mgmt.startTransfer(consumerPcid, agreementId, providerDsp);
        mgmt.awaitState("/participants/%s/transferprocesses/%s".formatted(consumerPcid, transferId),
                Duration.ofMinutes(3), Set.of("STARTED", "COMPLETED"));
        log("transfer process started: asset '%s' under agreement %s, transfer %s", assetId, agreementId, transferId);
    }

    /** All registration-status callbacks recorded by WireMock, keyed by externalId. */
    private Map<String, OnboardingResult> callbackResults() {
        return wiremock.findAll(postRequestedFor(urlPathEqualTo("/registration/status"))).stream()
                .map(request -> {
                    try {
                        return objectMapper.readValue(request.getBody(), OnboardingResult.class);
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                })
                .filter(result -> result.externalId() != null)
                .collect(Collectors.toMap(OnboardingResult::externalId, result -> result, (first, second) -> second));
    }

    private static void onboardParticipant(String name, String shortName, String runId) {
        var newParticipant = NewParticipantData.builder()
                .name(name)
                .shortName(shortName)
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

        // kick off the onboarding, then wait for the async callback
        given()
                .baseUri(VerificationEnvironmentE2ETest.ONBOARDING_API_URL)
                .contentType("application/json")
                .body(newParticipant)
                .post("/api/v2/administration/registration/Network/partnerRegistration")
                .then()
                .statusCode(200);
        log("onboarding submitted: \"%s\" (shortName=%s, externalId=%s)", name, shortName, runId);
    }
}
