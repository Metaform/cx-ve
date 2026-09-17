package com.metaform.cxve.verification.application;

import com.metaform.cxve.verification.adapter.out.certo.CertoClient;
import com.metaform.cxve.verification.adapter.out.did.DidWebResolver;
import com.metaform.cxve.verification.adapter.out.hub.MembershipHubClient;
import com.metaform.cxve.verification.adapter.out.management.CcmApi;
import com.metaform.cxve.verification.config.VerificationProperties;
import com.metaform.cxve.verification.domain.model.DidDocument;
import com.metaform.cxve.verification.domain.model.Membership;
import com.metaform.cxve.verification.domain.model.RunStep;
import com.metaform.cxve.verification.domain.model.VerificationRun;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * A verification run against a THIRD-PARTY system: an alternative vendor's connector and wallet,
 * already running outside this environment and reachable only over DSP and DCP. Same CX-0135
 * v3.0.0 Flow B exchange as the managed run, with the verification participant as the certificate
 * consumer — but this environment drives only its own half.
 *
 * <p>Everything on the system-under-test's side is an obligation IT fulfils, in its own time and
 * with whatever tooling it has (docs/sut-verification.md): requesting the credentials this
 * environment's issuer offers it, seeding the certificate offer this environment's participant
 * then consumes, and pushing a certificate over a flow it establishes itself. So the steps here
 * that concern the SUT are waits, not calls, and their timeouts bound a vendor's turnaround
 * rather than a deployment's — the operator is expected to stop a run rather than have it time
 * out at them.
 *
 * <p>What this costs in assertion strength is deliberate and worth naming: the certificate
 * arrives with content this environment never uploaded, so {@code RETRIEVE_AND_VERIFY} can only
 * check the delivery against itself, not against a known original. The exchange having happened
 * at all — a real negotiation, a real transfer, a real push, all under credentials this
 * environment's issuer signed — is the finding.
 */
@Component
public class ExternalCertificateExchangeFlow {

    private static final Logger log = LoggerFactory.getLogger(ExternalCertificateExchangeFlow.class);

    private final MembershipHubClient hub;
    private final CertoClient certo;
    private final DidWebResolver didResolver;
    private final VerificationParticipantService participantService;
    private final RunFlowSupport support;
    private final VerificationProperties properties;

    public ExternalCertificateExchangeFlow(MembershipHubClient hub,
                                           CertoClient certo,
                                           DidWebResolver didResolver,
                                           VerificationParticipantService participantService,
                                           RunFlowSupport support,
                                           VerificationProperties properties) {
        this.hub = hub;
        this.certo = certo;
        this.didResolver = didResolver;
        this.participantService = participantService;
        this.support = support;
        this.properties = properties;
    }

    public void execute(VerificationRun run) {
        var external = properties.external();
        log.info("run {} starting against the external participant {} (\"{}\", {})",
                run.id(), run.declaredDid(), run.name(), run.bpn());
        try {
            var vp = support.step(run, RunStep.ENSURE_VERIFICATION_PARTICIPANT, () -> {
                var participant = participantService.ensure();
                run.verificationParticipant(participant);
                return participant;
            }, participant -> "%s (pcid %s)".formatted(participant.bpn(), participant.participantContextId()));

            // Checkpoint 0: the participant is discoverable at all. Everything below dials the
            // endpoints this document advertises, so a run that gets past here has already
            // established that the SUT's identity resolves and names where to reach it.
            var didDocument = support.step(run, RunStep.RESOLVE_DID,
                    () -> didResolver.resolve(run.declaredDid()),
                    document -> "dsp %s".formatted(document.protocolEndpoint()));

            var membership = support.step(run, RunStep.ONBOARD_PARTICIPANT,
                    () -> onboard(run), member -> "externalId %s (%s)".formatted(member.externalId(),
                            member.isCredentialsOffered() ? "adopted" : "submitted"));

            support.step(run, RunStep.AWAIT_CREDENTIAL_OFFER, () -> {
                var offered = membership.isCredentialsOffered()
                        ? membership
                        : hub.awaitCredentialsOffered(membership.externalId());
                run.onExternallyOnboarded(offered.did(), offered.onboardingProcessId());
                return offered;
            }, offered -> "credentials offered to %s (process %s)".formatted(
                    offered.did(), offered.onboardingProcessId()));

            // The SUT's move: accept the offer, request the credentials, hold them. Until it has,
            // it cannot satisfy the policies on either side's offer — so this is the gate before
            // anything is dialled, and it doubles as the run's ledger verdict: onboarding and
            // credential delivery are everything the tracker can attribute to a participant this
            // environment does not host.
            support.step(run, RunStep.AWAIT_CREDENTIALS,
                    () -> support.evaluateEvents(run, external.expectedEvents(), external.credentialsTimeout()),
                    Function.identity());

            // The verification participant becomes consumer of the SUT's certificate offer — found
            // by the CX-0135 provider API it declares, since the asset id is the vendor's own
            // choice. The wait is for the SUT's operator to seed it; the negotiation that follows is
            // the real proof of its credentials, since this environment's offer is policy-gated.
            var flowIdPull = support.step(run, RunStep.ESTABLISH_PULL_FLOW,
                    () -> support.establishCcmFlow(vp.participantContextId(), didDocument.protocolEndpoint(),
                            run.declaredDid(), CcmApi.provider(properties.ccmApiVersion()),
                            external.providerOfferTimeout()),
                    flow -> "flowId %s (dataset '%s')".formatted(flow.flowId(), flow.datasetId())).flowId();

            var pushed = support.step(run, RunStep.AWAIT_PUBLISHED_CERTIFICATE,
                    () -> awaitPushedExchange(vp.participantContextId(), external.publishTimeout()),
                    exchange -> "exchange %s (certificate %s)".formatted(
                            exchange.path("exchangeId").asText(), exchange.path("certificateId").asText()));
            var exchangeId = pushed.path("exchangeId").asText();

            support.step(run, RunStep.RETRIEVE_AND_VERIFY,
                    () -> retrieveAndVerify(vp.participantContextId(), exchangeId, flowIdPull),
                    Function.identity());

            support.step(run, RunStep.ACCEPT, () -> {
                // Consumer-side only: the acceptance report to the provider is best-effort and
                // the provider here is the SUT, whose record this environment cannot read. So the
                // verdict is re-driven until THIS side has it recorded — a repeat with the same
                // verdict only re-reports, which is certo's documented recovery for a lost report.
                Poller.poll("exchange %s to be ACCEPTED on the consumer".formatted(exchangeId),
                        properties.timeouts().certo(), properties.pollInterval(), () -> {
                            certo.accept(vp.participantContextId(), exchangeId, "ACCEPTED", flowIdPull);
                            var recorded = findExchange(
                                    certo.consumerExchanges(vp.participantContextId(), false), exchangeId);
                            if (recorded == null || !"ACCEPTED".equals(recorded.path("acceptanceStatus").asText())) {
                                throw new Poller.RetryException("consumer view not final yet: "
                                        + (recorded == null ? "exchange absent" : recorded.path("acceptanceStatus").asText()));
                            }
                            return recorded;
                        });
                return "exchange %s closed: ACCEPTED".formatted(exchangeId);
            }, Function.identity());

            run.succeed();
            log.info("run {} SUCCEEDED", run.id());
        } catch (RuntimeException e) {
            run.fail(e.getMessage() == null ? e.toString() : e.getMessage());
            log.error("run {} FAILED: {}", run.id(), e.getMessage(), e);
        }
    }

    /**
     * The membership for this DID — reused when one already exists. A repeat run against the same
     * system must reuse it rather than register again: the Onboarding API treats an already
     * registered DID as a duplicate and would decline the second attempt, so re-onboarding is not
     * a fresh start but a guaranteed failure.
     */
    private Membership onboard(VerificationRun run) {
        var existing = hub.findByDid(run.declaredDid()).stream()
                .filter(Membership::isReusable)
                .findFirst()
                .orElse(null);
        if (existing != null) {
            log.info("run {} adopting the existing membership {} of {} (state {})",
                    run.id(), existing.externalId(), run.declaredDid(), existing.state());
            run.onSubmitted(existing.externalId());
            return existing;
        }
        var membership = hub.onboard(run.name(), run.shortName(), run.bpn(), run.vatId(), run.declaredDid());
        run.onSubmitted(membership.externalId());
        return membership;
    }

    /**
     * Waits for a certificate to arrive on the verification participant. Nothing on the wire
     * identifies it — the push opens the exchange on this side — so the consumer's reconciliation
     * query is the only way to see it, and exactly one exchange awaiting acceptance is expected:
     * the verification participant's inbox is otherwise idle for the length of a run.
     */
    private JsonNode awaitPushedExchange(String consumerPcid, java.time.Duration timeout) {
        return Poller.poll("a certificate pushed to the verification participant", timeout,
                properties.pollInterval(), () -> {
                    var items = certo.consumerExchanges(consumerPcid, true).path("items");
                    if (items.isEmpty()) {
                        throw new Poller.RetryException("no exchange awaiting acceptance yet");
                    }
                    if (items.size() > 1) {
                        // Ambiguous rather than wrong: picking one would silently verify an
                        // unrelated push (a concurrent run, or a leftover never accepted).
                        throw new VerificationException(("%d exchanges are awaiting acceptance on the "
                                + "verification participant — cannot tell which one this run's SUT pushed")
                                .formatted(items.size()));
                    }
                    return items.path(0);
                });
    }

    /**
     * What can be checked about a delivery whose content this environment did not author: the
     * exchange carries a certificate and at least one document, and the document's bytes are
     * actually there and match their declared media type's presence. The managed run compares
     * against the original it uploaded; here there is no original to compare against.
     */
    private String retrieveAndVerify(String consumerPcid, String exchangeId, String flowIdPull) {
        var retrieved = certo.retrieve(consumerPcid, exchangeId, flowIdPull);
        var certificateId = retrieved.path("certificate").path("certificateId").asText();
        if (certificateId.isEmpty()) {
            throw new VerificationException("retrieved exchange %s carries no certificate id: %s"
                    .formatted(exchangeId, retrieved));
        }
        var documents = retrieved.path("documents");
        if (documents.isEmpty()) {
            throw new VerificationException("retrieved certificate %s carries no document".formatted(certificateId));
        }
        var document = documents.path(0);
        var mediaType = document.path("mediaType").asText();
        if (mediaType.isEmpty()) {
            throw new VerificationException("retrieved document of %s declares no media type".formatted(certificateId));
        }
        var content = document.path("contentBase64").asText();
        if (content.isEmpty()) {
            throw new VerificationException("retrieved document of %s has no content".formatted(certificateId));
        }
        var bytes = java.util.Base64.getDecoder().decode(content);
        if (bytes.length == 0) {
            throw new VerificationException("retrieved document of %s decodes to nothing".formatted(certificateId));
        }
        return "certificate %s retrieved with %d document(s), first is %s (%d bytes)"
                .formatted(certificateId, documents.size(), mediaType, bytes.length);
    }

    /** One exchange out of a consumer exchange page, by id; null when the page has no such row. */
    private static JsonNode findExchange(JsonNode page, String exchangeId) {
        for (var item : page.path("items")) {
            if (exchangeId.equals(item.path("exchangeId").asText())) {
                return item;
            }
        }
        return null;
    }
}
