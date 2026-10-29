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
import java.time.Duration;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * A verification run against a THIRD-PARTY system: an alternative vendor's connector and wallet,
 * already running outside this environment and reachable only over DSP and DCP. Same DSAF SCE
 * exchange as the managed run, with the verification participant as the certificate consumer —
 * but this environment drives only its own half: it requests a certificate for the SUT's member id
 * and takes it from there.
 *
 * <p>Everything on the system-under-test's side is an obligation IT fulfils, in its own time and
 * with whatever tooling it has (docs/sut-verification.md): requesting the credentials this
 * environment's issuer offers it, seeding the provider-API offer this environment's participant
 * then consumes, and holding — or issuing, once asked — the requested certificate. So the steps
 * here that concern the SUT are waits, not calls, and their timeouts bound a vendor's turnaround
 * rather than a deployment's — the operator is expected to stop a run rather than have it time
 * out at them.
 *
 * <p>What this costs in assertion strength is deliberate and worth naming: the certificate
 * arrives with content this environment never uploaded, so {@code RETRIEVE_AND_VERIFY} can only
 * check the delivery against itself, not against a known original. The exchange having happened
 * at all — a real negotiation, a real transfer, a real request and retrieval, all under
 * credentials this environment's issuer signed — is the finding.
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
        var profile = properties.dataspace(run.dataspace());
        var ccm = profile.useCase(run.useCase()).ccm();
        log.info("run {} starting against the external {} participant {} (\"{}\", {})",
                run.id(), run.dataspace(), run.declaredDid(), run.name(), run.memberId());
        try {
            var vp = support.step(run, RunStep.ENSURE_VERIFICATION_PARTICIPANT, () -> {
                var participant = participantService.ensure(run.dataspace());
                run.verificationParticipant(participant);
                return participant;
            }, participant -> "%s (pcid %s)".formatted(participant.memberId(), participant.participantContextId()));

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
                // a dataspace that assigns member ids has done so by now
                run.adoptMemberId(offered.memberId());
                return offered;
            }, offered -> "credentials offered to %s (process %s)".formatted(
                    offered.did(), offered.onboardingProcessId()));

            // The SUT's move: accept the offer, request the credentials, hold them. Until it has,
            // it cannot satisfy the policies on either side's offer — so this is the gate before
            // anything is dialled, and it doubles as the run's ledger verdict: onboarding and
            // credential delivery are everything the tracker can attribute to a participant this
            // environment does not host.
            support.step(run, RunStep.AWAIT_CREDENTIALS,
                    () -> support.evaluateEvents(run, ccm.externalExpectedEvents(), external.credentialsTimeout()),
                    Function.identity());

            // The verification participant becomes consumer of the SUT's certificate offer — found
            // by the CCM provider API it declares, since the asset id is the vendor's own
            // choice. The wait is for the SUT's operator to seed it; the negotiation that follows is
            // the real proof of its credentials, since this environment's offer is policy-gated.
            var flowIdPull = support.step(run, RunStep.ESTABLISH_PULL_FLOW,
                    () -> support.establishCcmFlow(vp.participantContextId(), didDocument.protocolEndpoint(),
                            run.declaredDid(), profile.dspProfile(), CcmApi.provider(ccm.api()),
                            external.providerOfferTimeout()),
                    flow -> "flowId %s (dataset '%s')".formatted(flow.flowId(), flow.datasetId())).flowId();

            var fulfilled = support.step(run, RunStep.REQUEST_CERTIFICATE,
                    () -> requestCertificate(run, vp.participantContextId(), ccm, flowIdPull,
                            external.fulfillmentTimeout()),
                    exchange -> "exchange %s FULFILLED (certificate %s)".formatted(
                            exchange.path("exchangeId").asText(), exchange.path("certificateId").asText()));
            var exchangeId = fulfilled.path("exchangeId").asText();

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
     * system must reuse it rather than register again: an onboarding API treats an already
     * registered DID as a duplicate and would decline the second attempt, so re-onboarding is not
     * a fresh start but a guaranteed failure.
     */
    private Membership onboard(VerificationRun run) {
        var existing = hub.findByDid(run.dataspace(), run.declaredDid()).stream()
                .filter(Membership::isReusable)
                .findFirst()
                .orElse(null);
        if (existing != null) {
            log.info("run {} adopting the existing membership {} of {} (state {})",
                    run.id(), existing.externalId(), run.declaredDid(), existing.state());
            run.onSubmitted(existing.externalId());
            run.adoptMemberId(existing.memberId());
            return existing;
        }
        var membership = hub.onboard(run.dataspace(), run.name(), run.shortName(), run.memberId(), run.uniqueId(),
                run.declaredDid());
        run.onSubmitted(membership.externalId());
        return membership;
    }

    /**
     * The verification participant asks the SUT for a certificate of the dataspace's type certified
     * for the SUT's own member id. A provider holding one fulfils at once; one that does not leaves
     * the request RECEIVED until its backend issues the certificate and fulfils it, which this
     * environment observes by polling the SUT over the pull flow — so whether the SUT notifies about
     * the fulfillment does not matter. DECLINED ends the run with the SUT's stated grounds.
     */
    private JsonNode requestCertificate(VerificationRun run, String consumerPcid, VerificationProperties.Ccm ccm,
                                        String flowIdPull, Duration timeout) {
        var request = certo.requestCertificate(consumerPcid, RunFlowSupport.messageId(run, "request"), ccm.protocol(),
                run.declaredDid(), ccm.certificateType(), run.memberId(), flowIdPull);
        var exchangeId = request.path("exchangeId").asText();
        if (!"RECEIVED".equals(request.path("fulfillmentStatus").asText())) {
            return settled(exchangeId, request);
        }
        log.info("run {}: the SUT holds no matching certificate yet, exchange {} RECEIVED", run.id(), exchangeId);
        return Poller.poll("exchange %s to be fulfilled by the SUT".formatted(exchangeId), timeout,
                properties.pollInterval(), () -> settled(exchangeId, certo.pollRequest(consumerPcid, exchangeId, flowIdPull)));
    }

    /** The request's view once the SUT has decided it; still RECEIVED is "not yet". */
    private static JsonNode settled(String exchangeId, JsonNode request) {
        var status = request.path("fulfillmentStatus").asText();
        return switch (status) {
            case "FULFILLED" -> request;
            case "DECLINED" -> throw new VerificationException("the SUT declined the certificate request (exchange %s): %s"
                    .formatted(exchangeId, request.path("errors")));
            case "RECEIVED" -> throw new Poller.RetryException("exchange %s is still RECEIVED on the SUT".formatted(exchangeId));
            default -> throw new VerificationException("unexpected fulfillment status of exchange %s: %s"
                    .formatted(exchangeId, request));
        };
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
