package com.metaform.cxve.verification.application;

import com.metaform.cxve.verification.adapter.out.certo.CertoClient;
import com.metaform.cxve.verification.adapter.out.hub.MembershipHubClient;
import com.metaform.cxve.verification.adapter.out.management.ManagementApiClient;
import com.metaform.cxve.verification.config.VerificationProperties;
import com.metaform.cxve.verification.domain.model.RunStep;
import com.metaform.cxve.verification.domain.model.VerificationRun;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import static com.metaform.cxve.verification.application.ChecklistEvaluator.missing;
import static com.metaform.cxve.verification.application.ChecklistEvaluator.satisfied;

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
    private final ChecklistEvaluator evaluator;
    private final VerificationProperties properties;

    public CertificateExchangeFlow(MembershipHubClient hub,
                                   ManagementApiClient management,
                                   CertoClient certo,
                                   VerificationParticipantService participantService,
                                   ChecklistEvaluator evaluator,
                                   VerificationProperties properties) {
        this.hub = hub;
        this.management = management;
        this.certo = certo;
        this.participantService = participantService;
        this.evaluator = evaluator;
        this.properties = properties;
    }

    public void execute(VerificationRun run) {
        log.info("run {} starting: participant \"{}\" ({}, {})", run.id(), run.name(), run.shortName(), run.bpn());
        try {
            var vp = step(run, RunStep.ENSURE_VERIFICATION_PARTICIPANT, () -> {
                var participant = participantService.ensure();
                run.verificationParticipant(participant);
                return participant;
            }, participant -> "%s (pcid %s)".formatted(participant.bpn(), participant.participantContextId()));

            var submitted = step(run, RunStep.ONBOARD_PARTICIPANT, () -> {
                var membership = hub.onboard(run.name(), run.shortName(), run.bpn(), run.vatId());
                run.onSubmitted(membership.externalId());
                return membership;
            }, membership -> "externalId " + membership.externalId());

            var put = step(run, RunStep.AWAIT_PROVISIONED, () -> {
                var membership = hub.awaitProvisioned(submitted.externalId());
                run.onProvisioned(membership.did(), membership.participantContextId(), membership.onboardingProcessId());
                return membership;
            }, membership -> "pcid %s (process %s)".formatted(
                    membership.participantContextId(), membership.onboardingProcessId()));

            step(run, RunStep.AWAIT_CERTO_CONTEXT, () -> {
                certo.awaitParticipantContext(put.participantContextId());
                return "certo tenant " + put.participantContextId();
            }, Function.identity());

            var providerAssetId = "ccm-api-" + run.id();
            step(run, RunStep.SEED_PROVIDER_OFFER, () -> {
                seedCcmOffer(put.participantContextId(), providerAssetId, run.id());
                return "asset '%s' offered".formatted(providerAssetId);
            }, Function.identity());

            // "pull" flow: verification participant -> participant-under-test (retrieve + verdict)
            var flowIdPull = step(run, RunStep.ESTABLISH_PULL_FLOW,
                    () -> establishCcmFlow(vp.participantContextId(), put.participantContextId(), put.did(), providerAssetId),
                    flowId -> "flowId " + flowId);
            // "push" flow: participant-under-test -> verification participant (publish notification)
            var flowIdPush = step(run, RunStep.ESTABLISH_PUSH_FLOW,
                    () -> establishCcmFlow(put.participantContextId(), vp.participantContextId(), vp.did(), properties.inboxAssetId()),
                    flowId -> "flowId " + flowId);

            var documentContent = certificateDocument();
            var published = step(run, RunStep.PUBLISH_CERTIFICATE, () -> {
                var documentId = certo.addDocument(put.participantContextId(), "application/pdf", documentContent);
                var certificateId = certo.addCertificate(put.participantContextId(), put.bpn(), documentId,
                        "CXVE-VUI-9001-" + run.id());
                var exchangeId = certo.publish(put.participantContextId(), certificateId, vp.bpn(), vp.did(), flowIdPush);
                return new Published(documentId, certificateId, exchangeId);
            }, result -> "exchange %s (certificate %s)".formatted(result.exchangeId(), result.certificateId()));

            step(run, RunStep.RETRIEVE_AND_VERIFY, () -> {
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

            step(run, RunStep.ACCEPT, () -> {
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

            step(run, RunStep.EVALUATE_EVENTS, () -> evaluateEvents(run), Function.identity());

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
     * Polls the participant-under-test's eventlog until every expected event is there; keeps the
     * latest checklist on the run either way, so a timeout still shows exactly what was missing.
     */
    private String evaluateEvents(VerificationRun run) {
        var processId = run.onboardingProcessId();
        try {
            Poller.poll("expected events of participant %s in the eventlog".formatted(processId),
                    properties.timeouts().events(), properties.pollInterval(), () -> {
                        var rollup = hub.eventlog(processId).orElse(null);
                        var checklist = evaluator.evaluate(rollup, properties.expectedEvents());
                        run.checklist(checklist);
                        if (!satisfied(checklist)) {
                            throw new Poller.RetryException("missing: " + String.join(", ", missing(checklist)));
                        }
                        return checklist;
                    });
        } catch (VerificationException e) {
            throw new VerificationException("expected events did not all arrive — missing: "
                    + String.join(", ", missing(run.checklist())), e);
        }
        return "all %d expected event subjects present".formatted(properties.expectedEvents().size());
    }

    /**
     * One side's CCM offer: the asset fronting certo's protocol API, the access policy
     * (Membership) and use policy (FrameworkAgreement + UsagePurpose + DataUsageEndDefinition),
     * and the contract definition. Ids are run-scoped; creation is idempotent.
     */
    private void seedCcmOffer(String pcid, String assetId, String uniqueId) {
        var accessPolicyId = "vui-ccm-access-policy-" + uniqueId;
        var contractPolicyId = "vui-ccm-contract-policy-" + uniqueId;
        management.createAssetIdempotent(pcid, assetId, properties.certoAssetBaseUrl());
        management.createPolicyIdempotent(pcid, accessPolicyId, "access",
                List.of(ManagementApiClient.MEMBERSHIP_CONSTRAINT));
        management.createPolicyIdempotent(pcid, contractPolicyId, "use",
                List.of(ManagementApiClient.FRAMEWORK_AGREEMENT_CONSTRAINT,
                        ManagementApiClient.USAGE_PURPOSE_CONSTRAINT,
                        ManagementApiClient.DATA_USAGE_DEFINITION_CONSTRAINT));
        management.createContractDefinitionIdempotent(pcid, "vui-ccm-cd-" + uniqueId, accessPolicyId, contractPolicyId);
    }

    /**
     * Catalog → negotiation → transfer of the CCM transfer type, as {@code consumerPcid} against
     * {@code providerPcid}'s asset. Returns the CONSUMER-side transfer process id once STARTED —
     * the id under which Siglet cached the flow token, i.e. the {@code flowId} certo management
     * calls placed BY that consumer side must carry.
     */
    private String establishCcmFlow(String consumerPcid, String providerPcid, String providerDid, String assetId) {
        var providerDsp = properties.dspAddressOf(providerPcid);
        var offer = management.awaitCatalogOffer(consumerPcid, providerDsp, providerDid, assetId);
        var negotiationId = management.startNegotiation(consumerPcid, providerDsp, providerDid, assetId, offer);
        var negotiation = management.awaitState(
                "/participants/%s/contractnegotiations/%s".formatted(consumerPcid, negotiationId),
                properties.timeouts().negotiation(), Set.of("FINALIZED"));
        var agreementId = negotiation.path("contractAgreementId").asText();
        if (agreementId.isEmpty()) {
            throw new VerificationException("FINALIZED negotiation %s carries no contractAgreementId".formatted(negotiationId));
        }
        var transferId = management.startTransfer(consumerPcid, agreementId, providerDsp, properties.transferType());
        management.awaitState("/participants/%s/transferprocesses/%s".formatted(consumerPcid, transferId),
                properties.timeouts().transfer(), Set.of("STARTED"));
        log.info("CCM flow established: {} -> {} (asset '{}', flowId {})", consumerPcid, providerPcid, assetId, transferId);
        return transferId;
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

    /** Executes one step, recording start/ok/failure (with the exception message) on the run. */
    private <T> T step(VerificationRun run, RunStep runStep, Supplier<T> body, Function<T, String> detail) {
        run.stepStarted(runStep);
        try {
            var result = body.get();
            run.stepOk(runStep, detail == null || result == null ? null : detail.apply(result));
            return result;
        } catch (RuntimeException e) {
            run.stepFailed(runStep, e.getMessage() == null ? e.toString() : e.getMessage());
            throw e;
        }
    }

    private record Published(String documentId, String certificateId, String exchangeId) {
    }
}
