package com.metaform.cxve.verification.application;

import com.metaform.cxve.verification.adapter.out.certo.CertoClient;
import com.metaform.cxve.verification.adapter.out.hub.MembershipHubClient;
import com.metaform.cxve.verification.adapter.out.management.CcmApi;
import com.metaform.cxve.verification.adapter.out.management.ManagementApiClient;
import com.metaform.cxve.verification.config.VerificationProperties;
import com.metaform.cxve.verification.domain.model.RunStep;
import com.metaform.cxve.verification.domain.model.VerificationRun;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * The verification run: CX-0135 v3.0.0 Flow B (provider-initiated certificate push), the exact
 * sequence of the e2e suite's {@code certificateExchange()} — with the verification participant
 * as the permanent certificate CONSUMER and the freshly onboarded participant-under-test as the
 * certificate PROVIDER. Certo resolves every outbound CCM call from Siglet's flow cache, which
 * only ever fills on the DSP-consumer side of a flow, so each side that PLACES CCM calls first
 * becomes DSP consumer of a flow on the OTHER side's CCM asset:
 * <ul>
 *   <li>"push" flow: the participant-under-test consumes the verification participant's
 *       permanent inbox asset — its transfer id is the {@code flowId} of the publish;</li>
 *   <li>"pull" flow: the verification participant consumes the participant-under-test's
 *       run-scoped api asset — its transfer id is the {@code flowId} of retrieve + accept.</li>
 * </ul>
 * Every step is recorded on the run as it happens; the first failure ends the run, and the
 * verdict additionally requires the expected events in the compliance tracker's ledger.
 */
@Component
public class CertificateExchangeFlow {

    private static final Logger log = LoggerFactory.getLogger(CertificateExchangeFlow.class);

    private final MembershipHubClient hub;
    private final ManagementApiClient management;
    private final CertoClient certo;
    private final VerificationParticipantService participantService;
    private final RunFlowSupport support;
    private final VerificationProperties properties;

    public CertificateExchangeFlow(MembershipHubClient hub,
                                   ManagementApiClient management,
                                   CertoClient certo,
                                   VerificationParticipantService participantService,
                                   RunFlowSupport support,
                                   VerificationProperties properties) {
        this.hub = hub;
        this.management = management;
        this.certo = certo;
        this.participantService = participantService;
        this.support = support;
        this.properties = properties;
    }

    public void execute(VerificationRun run) {
        log.info("run {} starting: participant \"{}\" ({}, {})", run.id(), run.name(), run.shortName(), run.bpn());
        try {
            var vp = support.step(run, RunStep.ENSURE_VERIFICATION_PARTICIPANT, () -> {
                var participant = participantService.ensure();
                run.verificationParticipant(participant);
                return participant;
            }, participant -> "%s (pcid %s)".formatted(participant.bpn(), participant.participantContextId()));

            var submitted = support.step(run, RunStep.ONBOARD_PARTICIPANT, () -> {
                var membership = hub.onboard(run.name(), run.shortName(), run.bpn(), run.vatId());
                run.onSubmitted(membership.externalId());
                return membership;
            }, membership -> "externalId " + membership.externalId());

            var put = support.step(run, RunStep.AWAIT_PROVISIONED, () -> {
                var membership = hub.awaitProvisioned(submitted.externalId());
                run.onProvisioned(membership.did(), membership.participantContextId(), membership.onboardingProcessId());
                return membership;
            }, membership -> "pcid %s (process %s)".formatted(
                    membership.participantContextId(), membership.onboardingProcessId()));

            // Credentials reach a participant provisioned here exactly as they reach a third-party
            // one: the hub has the issuer offer them, and the participant's wallet requests them.
            // Nothing downstream works without them — every DSP message presents them — so the run
            // waits for the delivery the ledger records before it negotiates anything.
            support.step(run, RunStep.AWAIT_CREDENTIALS,
                    () -> support.evaluateEvents(run, Map.of(properties.credentialDeliverySubject(), 1)),
                    Function.identity());

            support.step(run, RunStep.AWAIT_CERTO_CONTEXT, () -> {
                certo.awaitParticipantContext(put.participantContextId());
                return "certo tenant " + put.participantContextId();
            }, Function.identity());

            var providerAssetId = "ccm-api-" + run.id();
            support.step(run, RunStep.SEED_PROVIDER_OFFER, () -> {
                seedCcmOffer(put.participantContextId(), providerAssetId, run.id());
                return "asset '%s' offered".formatted(providerAssetId);
            }, Function.identity());

            // "pull" flow: verification participant -> participant-under-test (retrieve + verdict)
            var flowIdPull = support.step(run, RunStep.ESTABLISH_PULL_FLOW,
                    () -> establishCcmFlow(vp.participantContextId(), put.participantContextId(), put.did(), providerAssetId),
                    flowId -> "flowId " + flowId);
            // "push" flow: participant-under-test -> verification participant (publish notification)
            var flowIdPush = support.step(run, RunStep.ESTABLISH_PUSH_FLOW,
                    () -> establishCcmFlow(put.participantContextId(), vp.participantContextId(), vp.did(), properties.inboxAssetId()),
                    flowId -> "flowId " + flowId);

            var documentContent = certificateDocument();
            var published = support.step(run, RunStep.PUBLISH_CERTIFICATE, () -> {
                var documentId = certo.addDocument(put.participantContextId(), "application/pdf", documentContent);
                var certificateId = certo.addCertificate(put.participantContextId(), put.bpn(), documentId,
                        "CXVE-VUI-9001-" + run.id());
                var exchangeId = certo.publish(put.participantContextId(), certificateId, vp.bpn(), vp.did(), flowIdPush);
                return new Published(documentId, certificateId, exchangeId);
            }, result -> "exchange %s (certificate %s)".formatted(result.exchangeId(), result.certificateId()));

            support.step(run, RunStep.RETRIEVE_AND_VERIFY, () -> {
                var retrieved = certo.retrieve(vp.participantContextId(), published.exchangeId(), flowIdPull);
                var retrievedCertificateId = retrieved.path("certificate").path("certificateId").asText();
                if (!published.certificateId().equals(retrievedCertificateId)) {
                    throw new VerificationException("retrieved certificate id '%s' does not match the published '%s'"
                            .formatted(retrievedCertificateId, published.certificateId()));
                }
                if (retrieved.path("documents").size() != 1) {
                    throw new VerificationException("expected exactly 1 document, got: " + retrieved.path("documents"));
                }
                var document = retrieved.path("documents").path(0);
                if (!published.documentId().equals(document.path("documentId").asText())
                        || !"application/pdf".equals(document.path("mediaType").asText())) {
                    throw new VerificationException("retrieved document does not match the upload: " + document);
                }
                var downloaded = Base64.getDecoder().decode(document.path("contentBase64").asText());
                if (!Arrays.equals(downloaded, documentContent)) {
                    throw new VerificationException("downloaded document differs from the uploaded one (%d vs %d bytes)"
                            .formatted(downloaded.length, documentContent.length));
                }
                return "document verified byte-for-byte (%d bytes)".formatted(downloaded.length);
            }, Function.identity());

            support.step(run, RunStep.ACCEPT, () -> {
                // the acceptance report to the provider is best-effort (post-commit), so the
                // verdict is re-driven until the PROVIDER's recorded view shows it — certo's
                // documented recovery for a lost report (no state change, re-report)
                Poller.poll("exchange %s to be FULFILLED/ACCEPTED on the provider".formatted(published.exchangeId()),
                        properties.timeouts().certo(), properties.pollInterval(), () -> {
                            certo.accept(vp.participantContextId(), published.exchangeId(), "ACCEPTED", flowIdPull);
                            var exchange = certo.getExchange(put.participantContextId(), published.exchangeId());
                            if (!"FULFILLED".equals(exchange.path("fulfillmentStatus").asText())
                                    || !"ACCEPTED".equals(exchange.path("acceptanceStatus").asText())) {
                                throw new Poller.RetryException("provider view not final yet: fulfillment=%s, acceptance=%s"
                                        .formatted(exchange.path("fulfillmentStatus").asText(),
                                                exchange.path("acceptanceStatus").asText()));
                            }
                            return exchange;
                        });
                return "exchange %s closed: FULFILLED / ACCEPTED".formatted(published.exchangeId());
            }, Function.identity());

            support.step(run, RunStep.EVALUATE_EVENTS,
                    () -> support.evaluateEvents(run, properties.expectedEvents()), Function.identity());

            run.succeed();
            log.info("run {} SUCCEEDED", run.id());
        } catch (RuntimeException e) {
            // stepFailed already recorded the step-level reason; this is the terminal backstop
            // (and the only recorder for failures outside any step)
            run.fail(e.getMessage() == null ? e.toString() : e.getMessage());
            log.error("run {} FAILED: {}", run.id(), e.getMessage(), e);
        }
    }

    /**
     * One side's CCM offer: the asset fronting certo's protocol API, the access policy
     * (Membership) and use policy (FrameworkAgreement + UsagePurpose + DataUsageEndDefinition),
     * and the contract definition. Ids are run-scoped; creation is idempotent.
     */
    private void seedCcmOffer(String pcid, String assetId, String uniqueId) {
        var accessPolicyId = "vui-ccm-access-policy-" + uniqueId;
        var contractPolicyId = "vui-ccm-contract-policy-" + uniqueId;
        management.upsertAsset(pcid, assetId, CcmApi.provider(properties.ccmApiVersion()));
        management.createPolicyIdempotent(pcid, accessPolicyId, "access",
                List.of(ManagementApiClient.MEMBERSHIP_CONSTRAINT));
        management.createPolicyIdempotent(pcid, contractPolicyId, "use",
                List.of(ManagementApiClient.FRAMEWORK_AGREEMENT_CONSTRAINT,
                        ManagementApiClient.USAGE_PURPOSE_CONSTRAINT,
                        ManagementApiClient.DATA_USAGE_DEFINITION_CONSTRAINT));
        management.createContractDefinitionIdempotent(pcid, "vui-ccm-cd-" + uniqueId, accessPolicyId, contractPolicyId);
    }

    /** This side consuming the other's CCM asset, at the DSP address its context is served on. */
    private String establishCcmFlow(String consumerPcid, String providerPcid, String providerDid, String assetId) {
        return support.establishCcmFlow(consumerPcid, properties.dspAddressOf(providerPcid), providerDid, assetId,
                properties.timeouts().catalog()).flowId();
    }

    /** The sample certificate document packaged with the app (a small single-page PDF). */
    private static byte[] certificateDocument() {
        try (var stream = CertificateExchangeFlow.class.getResourceAsStream("/certificate-document.pdf")) {
            if (stream == null) {
                throw new VerificationException("certificate-document.pdf missing from the application resources");
            }
            return stream.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private record Published(String documentId, String certificateId, String exchangeId) {
    }
}
