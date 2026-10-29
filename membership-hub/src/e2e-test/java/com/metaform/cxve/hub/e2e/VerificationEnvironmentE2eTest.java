package com.metaform.cxve.hub.e2e;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.Set;
import java.util.UUID;

import static com.metaform.cxve.hub.e2e.TestLog.log;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * End-to-end tests against a running VE (stood up by scripts/install-ve.sh) through its
 * gateway — black box, no dependency on the app modules' classes; wire payloads are mirrored
 * locally. Participants are onboarded into DECADE-X through the Membership Hub — the full
 * journey: EDC resource provisioning via the CFM Tenant Manager, then the onboarding request to
 * the DECADE-X onboarding API, approved automatically (see {@link MembershipHubApi}). The suite is not part of
 * {@code check}/{@code build}; run it with {@code ./gradlew e2eTest}.
 */
class VerificationEnvironmentE2eTest {
    private static final String MEMBERSHIP_HUB_URL = "http://cxve.localhost/hub";
    private static final String TOKEN_EXCHANGE_URL = "http://cxve.localhost/api/auth/token";
    private static final String MANAGEMENT_API_URL = "http://cxve.localhost/api/management/v5";
    private static final String CERTO_API_URL = "http://cxve.localhost/api/certo/management/v1";
    // Transfer type of the CCM flows: the Data Plane Signaling HTTP transfer profile's pull value.
    // It keys the participant's siglet transfer-type mapping (installed at provisioning from
    // participant.ccm.* config), the dataplane registration, and the transfer request.
    private static final String CCM_TRANSFER_TYPE = "https://w3id.org/dspace-sig/profile/http-pull";
    // SCE certificate types are lowercase identifiers (certo rejects "ISO9001" with 400)
    private static final String CERTIFICATE_TYPE = "iso9001";
    // kubectl must target the VE cluster regardless of the current kubectl context
    private static final String KUBECONFIG = System.getenv().getOrDefault("KUBECONFIG",
            System.getProperty("user.home") + "/.kube/cxve.config");

    private final TokenExchange tokenExchange = new TokenExchange(TOKEN_EXCHANGE_URL, KUBECONFIG);
    private final MembershipHubApi hub = new MembershipHubApi(MEMBERSHIP_HUB_URL);

    /**
     * DSAF SCE provider-notified exchange, full loop: the provider issues a certificate and
     * notifies the consumer, the consumer requests it (fulfilled at once — the provider holds it),
     * retrieves it and accepts. Certo resolves every outbound SCE call's token + counterparty
     * endpoint from Siglet's flow cache, populated when a data transfer of the CCM transfer type
     * reaches STARTED — and only ever on the DSP-consumer side of a flow. So each side that PLACES
     * SCE calls first becomes the DSP consumer of a flow on the OTHER side's SCE asset:
     * <ul>
     *   <li>flow "push": cert-provider consumes the cert-consumer's consumer-API asset — its
     *       transfer id is the {@code flowId} of the notify (provider -> consumer);</li>
     *   <li>flow "pull": cert-consumer consumes the cert-provider's provider-API asset — its
     *       transfer id is the {@code flowId} of request, retrieve + accept (consumer -> provider).</li>
     * </ul>
     * Siglet stamps the caller's DECADE-X-ID (from its Legal Entity VC) into the flow token as the
     * optional {@code dataspaceidentifier} claim (alongside sub = caller DID, aud = receiving
     * tenant DID — the identities certo correlates on).
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

        // both sides offer their SCE API asset (certo's protocol API behind the CCM transfer type)
        var providerAssetId = "sce-provider-api-" + runId;
        var consumerAssetId = "sce-consumer-api-" + runId;
        seedSceOffer(mgmt, providerPcid, providerAssetId, ManagementApi.SCE_PROVIDER_API, "p-" + runId);
        seedSceOffer(mgmt, consumerPcid, consumerAssetId, ManagementApi.SCE_CONSUMER_API, "c-" + runId);

        // "pull" flow: cert-consumer -> cert-provider (request, retrieve + acceptance report)
        var flowIdPull = establishCcmFlow(mgmt, consumerPcid, providerPcid, provider.holderId(), providerAssetId);
        // "push" flow: cert-provider -> cert-consumer (the lifecycle notification)
        var flowIdPush = establishCcmFlow(mgmt, providerPcid, consumerPcid, consumer.holderId(), consumerAssetId);

        // the certo SA is mapped (resource "sudo") to the certo-mgmt-api scopes by the
        // umbrella's certo-jwtlet-seed; certo validates the token itself (no clearglass)
        var certo = new CertoApi(CERTO_API_URL,
                tokenExchange.getParticipantToken("certo", "sudo", "certo-mgmt-api:write"));
        log("certo-API token obtained (certo -> sudo, scope certo-mgmt-api:write)");
        certo.awaitParticipantContext(providerPcid, Duration.ofMinutes(2));
        certo.awaitParticipantContext(consumerPcid, Duration.ofMinutes(2));

        // the backend issues the certificate (state only), then the provider notifies the consumer
        // that it is AVAILABLE — lifecycle only, no exchange is opened. The document is the
        // checked-in sample PDF from the suite's resources; the later download is compared
        // against it byte-for-byte to prove a real content round-trip.
        var documentContent = certificateDocument();
        var documentId = certo.addDocument(providerPcid, "application/pdf", documentContent);
        var certificateId = certo.addCertificate(providerPcid, provider.memberId(), documentId);
        certo.notify(providerPcid, certificateId, consumer.holderId(), flowIdPush);

        // the consumer requests the certificate it was told about; the provider holds it, so the
        // exchange is FULFILLED at once and discloses the issued certificate
        var request = certo.requestCertificate(consumerPcid, provider.holderId(), CERTIFICATE_TYPE,
                provider.memberId(), flowIdPull);
        assertThat(request.path("fulfillmentStatus").asText())
                .withFailMessage("a request for a held certificate must be fulfilled immediately: %s", request)
                .isEqualTo("FULFILLED");
        assertThat(request.path("certificateId").asText()).isEqualTo(certificateId);
        var exchangeId = request.path("exchangeId").asText();

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
     * The consumer-INITIATED request, the counterpart of {@link #certificateExchange()}'s
     * notified one.
     *
     * <p>The distinction matters beyond coverage. A request for a certificate the provider already
     * holds is {@code FULFILLED} on creation, so it never holds the waiting state. Only this flow
     * walks the SCE fulfillment state machine from the start: the consumer asks for a certificate
     * the provider does not yet hold, the exchange waits in {@code RECEIVED} with <b>no certificate
     * identity at all</b>, and a later fulfillment binds one. That is also the only flow in which
     * certo emits {@code org.metaform.certo.CertificateExchangeReceived.v1} (subject
     * {@code events.certificate.exchange.received}).
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

        var providerAssetId = "sce-provider-api-" + runId;
        var consumerAssetId = "sce-consumer-api-" + runId;
        seedSceOffer(mgmt, providerPcid, providerAssetId, ManagementApi.SCE_PROVIDER_API, "p-" + runId);
        seedSceOffer(mgmt, consumerPcid, consumerAssetId, ManagementApi.SCE_CONSUMER_API, "c-" + runId);

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
        var request = certo.requestCertificate(consumerPcid, provider.holderId(), CERTIFICATE_TYPE,
                provider.memberId(), flowIdPull);
        assertThat(request.path("fulfillmentStatus").asText()).isEqualTo("RECEIVED");
        var exchangeId = request.path("exchangeId").asText();

        // the provider's record exists and is waiting, with NO certificate bound — the property that
        // distinguishes it from a request for a held certificate
        var pending = certo.getExchange(providerPcid, exchangeId);
        assertThat(pending.path("fulfillmentStatus").asText()).isEqualTo("RECEIVED");
        assertThat(pending.path("certificateId").isNull() || pending.path("certificateId").asText().isEmpty())
                .withFailMessage("a request the provider cannot yet satisfy must carry no certificate: %s", pending)
                .isTrue();
        log("request %s open on the provider, awaiting issuance (%s)",
                exchangeId, pending.path("fulfillmentStatus").asText());

        // 2. the backend issues the certificate, which now covers the waiting request
        var documentContent = certificateDocument();
        var documentId = certo.addDocument(providerPcid, "application/pdf", documentContent);
        var certificateId = certo.addCertificate(providerPcid, provider.memberId(), documentId);

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

        // 5. same client-driven tail as the notified flow: retrieve the content, then a terminal verdict
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
     * Onboards a provider + consumer pair through the Membership Hub and waits until both have
     * been offered their credentials — the terminal state, and the point from which their wallets
     * can present anything. The returned {@link OnboardingResult}s carry the provisioned
     * identities straight from the hub's correlated record — participant context id included, so
     * nothing needs to be recovered from the Tenant Manager here.
     */
    private OnboardedPair onboardProviderAndConsumer(String runId) {
        var provider = onboardMember("Provider " + runId, "provider-" + runId);
        var consumer = onboardMember("Consumer " + runId, "consumer-" + runId);
        log("provider onboarded: pcid=%s did=%s DECADE-X-ID=%s", provider.participantContextId(), provider.holderId(), provider.memberId());
        log("consumer onboarded: pcid=%s did=%s DECADE-X-ID=%s", consumer.participantContextId(), consumer.holderId(), consumer.memberId());
        return new OnboardedPair(provider, consumer);
    }

    private OnboardingResult onboardMember(String name, String shortName) {
        // no member id: the hub generates the DECADE-X-ID of a member it hosts. The VAT id is
        // derived from the run-unique short name (duplicates of active registrations are rejected)
        var submitted = hub.onboard(name, shortName, "DE" + String.format("%08X", Math.abs(shortName.hashCode())));
        var externalId = submitted.path("externalId").asText();
        var provisioned = hub.awaitProvisioned(externalId, Duration.ofMinutes(10));
        var pcid = provisioned.path("participantContextId").asText();
        // Guard against wire-contract skew: a membership this environment provisioned always
        // carries the context id, so an empty value means the deployed hub serves a different
        // field name than this suite mirrors — fail here, not as an opaque 4xx three steps later.
        assertThat(pcid)
                .withFailMessage("provisioned membership %s carries no participantContextId — "
                        + "is the deployed hub older than this suite? Response: %s", externalId, provisioned)
                .isNotBlank();
        var memberId = provisioned.path("memberId").asText();
        assertThat(memberId)
                .withFailMessage("provisioned membership %s carries no DECADE-X-ID: %s", externalId, provisioned)
                .isNotBlank();
        return new OnboardingResult(externalId, memberId, provisioned.path("did").asText(), pcid);
    }

    private record OnboardedPair(OnboardingResult provider, OnboardingResult consumer) {
    }

    /**
     * Seeds one side's SCE offer: the asset fronting certo's protocol API, identified in the
     * catalog by the SCE API it {@code dct:conformsTo}, constraint-free access/contract policies
     * (DECADE-X has no policy vocabulary yet) and the contract definition. Ids are run-scoped;
     * policy/definition ids may repeat across the two participant contexts (resources are
     * context-scoped).
     */
    private void seedSceOffer(ManagementApi mgmt, String pcid, String assetId, String conformsTo, String uniqueId) {
        var accessPolicyId = "e2e-sce-access-policy-" + uniqueId;
        var contractPolicyId = "e2e-sce-contract-policy-" + uniqueId;

        mgmt.createSceAsset(pcid, assetId, conformsTo);
        mgmt.createPolicy(pcid, accessPolicyId, "access");
        mgmt.createPolicy(pcid, contractPolicyId, "use");
        mgmt.createContractDefinition(pcid, "e2e-sce-cd-" + uniqueId, accessPolicyId, contractPolicyId);
    }

    /**
     * Catalog -> negotiation -> transfer of the CCM transfer type, as {@code consumerPcid}
     * against {@code providerPcid}'s asset. Returns the CONSUMER-side transfer process id once
     * STARTED — the id under which Siglet cached the flow token, i.e. the {@code flowId} certo
     * management calls placed BY that consumer side must carry.
     */
    private String establishCcmFlow(ManagementApi mgmt, String consumerPcid, String providerPcid,
                                    String providerDid, String assetId) {
        var providerDsp = "http://cxve.localhost/api/dsp/%s/%s".formatted(providerPcid, ManagementApi.DSP_PROFILE);
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
}
