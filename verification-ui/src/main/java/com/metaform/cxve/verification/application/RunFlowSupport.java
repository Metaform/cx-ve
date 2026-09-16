package com.metaform.cxve.verification.application;

import com.metaform.cxve.verification.adapter.out.hub.MembershipHubClient;
import com.metaform.cxve.verification.adapter.out.management.ManagementApiClient;
import com.metaform.cxve.verification.config.VerificationProperties;
import com.metaform.cxve.verification.domain.model.RunStep;
import com.metaform.cxve.verification.domain.model.VerificationRun;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import static com.metaform.cxve.verification.application.ChecklistEvaluator.missing;
import static com.metaform.cxve.verification.application.ChecklistEvaluator.satisfied;

/**
 * What both run flows do identically, whether the participant-under-test lives in this
 * environment or elsewhere: record a step on the run, become DSP consumer of a counterparty's CCM
 * asset, and judge the run against the compliance tracker's ledger. Everything that differs —
 * who seeds which offer, who publishes, who is merely waited for — stays in the flows.
 */
@Component
public class RunFlowSupport {

    private static final Logger log = LoggerFactory.getLogger(RunFlowSupport.class);

    private final ManagementApiClient management;
    private final MembershipHubClient hub;
    private final ChecklistEvaluator evaluator;
    private final VerificationProperties properties;

    public RunFlowSupport(ManagementApiClient management,
                          MembershipHubClient hub,
                          ChecklistEvaluator evaluator,
                          VerificationProperties properties) {
        this.management = management;
        this.hub = hub;
        this.evaluator = evaluator;
        this.properties = properties;
    }

    /** Executes one step, recording start/ok/failure (with the exception message) on the run. */
    public <T> T step(VerificationRun run, RunStep runStep, Supplier<T> body, Function<T, String> detail) {
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

    /**
     * Catalog → negotiation → transfer of the CCM transfer type, as {@code consumerPcid} against
     * the counterparty's asset at {@code providerDsp}. Returns the CONSUMER-side transfer process
     * id once STARTED — the id under which Siglet cached the flow token, i.e. the {@code flowId}
     * certo management calls placed BY that consumer side must carry.
     *
     * <p>{@code catalogTimeout} is a parameter because the wait means different things: for an
     * offer this environment seeded itself it is a settling delay, for a third party's it is the
     * SUT's own turnaround.
     */
    public String establishCcmFlow(String consumerPcid, String providerDsp, String providerDid,
                                   String assetId, Duration catalogTimeout) {
        var offer = management.awaitCatalogOffer(consumerPcid, providerDsp, providerDid, assetId, catalogTimeout);
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
        log.info("CCM flow established: {} consuming '{}' at {} (flowId {})",
                consumerPcid, assetId, providerDsp, transferId);
        return transferId;
    }

    /**
     * Polls the participant-under-test's eventlog until every expected event is there; keeps the
     * latest checklist on the run either way, so a timeout still shows exactly what was missing.
     */
    public String evaluateEvents(VerificationRun run, Map<String, Integer> expectedEvents) {
        return evaluateEvents(run, expectedEvents, properties.timeouts().events());
    }

    /**
     * As above with an explicit budget: an external run waits here for its participant to request
     * and receive its credentials, which is the participant's own turnaround rather than this
     * environment's event propagation.
     */
    public String evaluateEvents(VerificationRun run, Map<String, Integer> expectedEvents, Duration timeout) {
        var processId = run.onboardingProcessId();
        try {
            Poller.poll("expected events of participant %s in the eventlog".formatted(processId),
                    timeout, properties.pollInterval(), () -> {
                        var rollup = hub.eventlog(processId).orElse(null);
                        var checklist = evaluator.evaluate(rollup, expectedEvents);
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
        return "all %d expected event subjects present".formatted(expectedEvents.size());
    }
}
