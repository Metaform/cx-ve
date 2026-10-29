package com.metaform.cxve.verification.application;

import com.metaform.cxve.verification.adapter.out.certo.CertoClient;
import com.metaform.cxve.verification.adapter.out.hub.MembershipHubClient;
import com.metaform.cxve.verification.adapter.out.management.CcmApi;
import com.metaform.cxve.verification.adapter.out.management.ManagementApiClient;
import com.metaform.cxve.verification.config.VerificationProperties;
import com.metaform.cxve.verification.config.VerificationProperties.DataspaceProfile;
import com.metaform.cxve.verification.domain.model.RunStep;
import com.metaform.cxve.verification.domain.model.VerificationRun;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Arrays;
import java.util.Base64;
import java.util.Map;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * The CCM verification run: a certificate exchange as the CCM standard (Certo's current release:
 * DSAF SCE) defines it, the exact sequence of the e2e suite's {@code certificateExchange()} — with
 * the verification participant as the permanent certificate CONSUMER and the freshly onboarded
 * participant-under-test as the certificate PROVIDER. The provider issues a certificate and notifies
 * the consumer it is AVAILABLE; the consumer then requests it, which the provider fulfils at once
 * because it holds it, retrieves it and reports its acceptance. Certo resolves every outbound SCE
 * call from Siglet's flow cache, which only ever fills on the DSP-consumer side of a flow, so each
 * side that PLACES SCE calls first becomes DSP consumer of a flow on the OTHER side's API asset:
 * <ul>
 *   <li>"push" flow: the participant-under-test consumes the verification participant's
 *       permanent inbox asset (its consumer API) — its transfer id is the {@code flowId} of the
 *       notification;</li>
 *   <li>"pull" flow: the verification participant consumes the participant-under-test's
 *       run-scoped provider API asset — its transfer id is the {@code flowId} of request, retrieve
 *       and accept.</li>
 * </ul>
 * Every step is recorded on the run as it happens; the first failure ends the run, and the
 * verdict additionally requires the expected events in the compliance tracker's ledger. What the
 * dataspace decides — DSP profile, policies, CCM API vocabulary, member ids — comes from the run's
 * dataspace profile.
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
        log.info("run {} starting: {} participant \"{}\" ({}, {})",
                run.id(), run.dataspace(), run.name(), run.shortName(), run.memberId());
        var profile = properties.dataspace(run.dataspace());
        var ccm = profile.useCase(run.useCase()).ccm();
        try {
            var vp = support.step(run, RunStep.ENSURE_VERIFICATION_PARTICIPANT, () -> {
                var participant = participantService.ensure(run.dataspace());
                run.verificationParticipant(participant);
                return participant;
            }, participant -> "%s (pcid %s)".formatted(participant.memberId(), participant.participantContextId()));

            var submitted = support.step(run, RunStep.ONBOARD_PARTICIPANT, () -> {
                var membership = hub.onboard(run.dataspace(), run.name(), run.shortName(), run.memberId(),
                        run.uniqueId(), null);
                run.onSubmitted(membership.externalId());
                // a dataspace that assigns member ids has the hub generate the hosted participant's
                run.adoptMemberId(membership.memberId());
                return membership;
            }, membership -> "externalId " + membership.externalId());

            var put = support.step(run, RunStep.AWAIT_PROVISIONED, () -> {
                var membership = hub.awaitProvisioned(submitted.externalId());
                run.onProvisioned(membership.did(), membership.participantContextId(), membership.onboardingProcessId());
                return membership;
            }, membership -> "pcid %s (process %s)".formatted(
                    membership.participantContextId(), membership.onboardingProcessId()));

            // Credentials reach a participant provisioned here exactly as they reach a third-party
            // one: its registration has the issuer offer them, and its wallet requests them.
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
                seedCcmOffer(profile, put.participantContextId(), providerAssetId, run.id());
                return "asset '%s' offered".formatted(providerAssetId);
            }, Function.identity());

            // "pull" flow: verification participant -> participant-under-test (retrieve + verdict)
            var flowIdPull = support.step(run, RunStep.ESTABLISH_PULL_FLOW,
                    () -> establishCcmFlow(profile, vp.participantContextId(), put.participantContextId(), put.did(),
                            providerAssetId),
                    flowId -> "flowId " + flowId);
            // "push" flow: participant-under-test -> verification participant (publish notification)
            var flowIdPush = support.step(run, RunStep.ESTABLISH_PUSH_FLOW,
                    () -> establishCcmFlow(profile, put.participantContextId(), vp.participantContextId(), vp.did(),
                            ccm.inboxAssetId()),
                    flowId -> "flowId " + flowId);

            var documentContent = certificateDocument();
            var issued = support.step(run, RunStep.ISSUE_CERTIFICATE, () -> {
                var documentId = certo.addDocument(put.participantContextId(), "application/pdf", documentContent);
                var certificateId = certo.addCertificate(put.participantContextId(), ccm.certificateType(),
                        put.memberId(), documentId, "VE-VUI-9001-" + run.id(), ccm.certificate());
                return new Issued(documentId, certificateId);
            }, result -> "certificate %s (%s, holder %s)".formatted(
                    result.certificateId(), ccm.certificateType(), put.memberId()));

            support.step(run, RunStep.NOTIFY_AVAILABLE, () -> {
                certo.notify(put.participantContextId(), issued.certificateId(), RunFlowSupport.messageId(run, "notify"),
                        ccm.protocol(), vp.did(), flowIdPush);
                return "%s notified: certificate %s AVAILABLE".formatted(vp.did(), issued.certificateId());
            }, Function.identity());

            // the provider holds the certificate by now, so it fulfils the request at once
            var exchangeId = support.step(run, RunStep.REQUEST_CERTIFICATE, () -> {
                var request = certo.requestCertificate(vp.participantContextId(), RunFlowSupport.messageId(run, "request"),
                        ccm.protocol(), put.did(), ccm.certificateType(), put.memberId(), flowIdPull);
                var status = request.path("fulfillmentStatus").asText();
                if (!"FULFILLED".equals(status)) {
                    throw new VerificationException("the request for the issued certificate was not fulfilled at once: "
                            + request);
                }
                if (!issued.certificateId().equals(request.path("certificateId").asText())) {
                    throw new VerificationException("the request was fulfilled with certificate '%s', not the issued '%s'"
                            .formatted(request.path("certificateId").asText(), issued.certificateId()));
                }
                return request.path("exchangeId").asText();
            }, id -> "exchange %s FULFILLED (certificate %s)".formatted(id, issued.certificateId()));

            support.step(run, RunStep.RETRIEVE_AND_VERIFY, () -> {
                var retrieved = certo.retrieve(vp.participantContextId(), exchangeId, flowIdPull);
                var retrievedCertificateId = retrieved.path("certificate").path("certificateId").asText();
                if (!issued.certificateId().equals(retrievedCertificateId)) {
                    throw new VerificationException("retrieved certificate id '%s' does not match the issued '%s'"
                            .formatted(retrievedCertificateId, issued.certificateId()));
                }
                if (retrieved.path("documents").size() != 1) {
                    throw new VerificationException("expected exactly 1 document, got: " + retrieved.path("documents"));
                }
                var document = retrieved.path("documents").path(0);
                if (!issued.documentId().equals(document.path("documentId").asText())
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
                Poller.poll("exchange %s to be FULFILLED/ACCEPTED on the provider".formatted(exchangeId),
                        properties.timeouts().certo(), properties.pollInterval(), () -> {
                            certo.accept(vp.participantContextId(), exchangeId, "ACCEPTED", flowIdPull);
                            var exchange = certo.getExchange(put.participantContextId(), exchangeId);
                            if (!"FULFILLED".equals(exchange.path("fulfillmentStatus").asText())
                                    || !"ACCEPTED".equals(exchange.path("acceptanceStatus").asText())) {
                                throw new Poller.RetryException("provider view not final yet: fulfillment=%s, acceptance=%s"
                                        .formatted(exchange.path("fulfillmentStatus").asText(),
                                                exchange.path("acceptanceStatus").asText()));
                            }
                            return exchange;
                        });
                return "exchange %s closed: FULFILLED / ACCEPTED".formatted(exchangeId);
            }, Function.identity());

            support.step(run, RunStep.EVALUATE_EVENTS,
                    () -> support.evaluateEvents(run, ccm.expectedEvents()), Function.identity());

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
     * One side's CCM offer: the asset fronting certo's provider API, the dataspace's access and use
     * policies (none yet in DECADE-X), and the contract definition. Ids are run-scoped; creation is idempotent.
     */
    private void seedCcmOffer(DataspaceProfile profile, String pcid, String assetId, String uniqueId) {
        var accessPolicyId = "vui-ccm-access-policy-" + uniqueId;
        var contractPolicyId = "vui-ccm-contract-policy-" + uniqueId;
        management.upsertAsset(pcid, assetId, CcmApi.provider(profile.useCase("ccm").ccm().api()));
        management.createPolicyIdempotent(pcid, accessPolicyId, "access", profile.policyContext(),
                profile.accessConstraints());
        management.createPolicyIdempotent(pcid, contractPolicyId, "use", profile.policyContext(),
                profile.contractConstraints());
        management.createContractDefinitionIdempotent(pcid, "vui-ccm-cd-" + uniqueId, accessPolicyId, contractPolicyId);
    }

    /** This side consuming the other's CCM asset, at the DSP address its context is served on. */
    private String establishCcmFlow(DataspaceProfile profile, String consumerPcid, String providerPcid,
                                    String providerDid, String assetId) {
        return support.establishCcmFlow(consumerPcid, properties.dspAddressOf(providerPcid, profile.dspProfile()),
                providerDid, profile.dspProfile(), assetId, properties.timeouts().catalog()).flowId();
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

    private record Issued(String documentId, String certificateId) {
    }
}
