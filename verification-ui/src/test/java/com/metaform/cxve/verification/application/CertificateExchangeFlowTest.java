package com.metaform.cxve.verification.application;

import com.metaform.cxve.verification.adapter.out.certo.CertoClient;
import com.metaform.cxve.verification.adapter.out.hub.MembershipHubClient;
import com.metaform.cxve.verification.adapter.out.management.ManagementApiClient;
import com.metaform.cxve.verification.domain.model.RunState;
import com.metaform.cxve.verification.domain.model.RunStep;
import com.metaform.cxve.verification.domain.model.StepStatus;
import com.metaform.cxve.verification.domain.model.VerificationParticipant;
import com.metaform.cxve.verification.domain.model.VerificationRun;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The run state machine against mocked clients: the happy path (SUCCEEDED, all steps OK, flow
 * ids on the correct sides), fail-fast on provisioning and on a TERMINATED negotiation (later
 * steps SKIPPED), and the missing-events verdict (checklist kept on the run). Lenient stubbing:
 * the failure cases deliberately stop mid-choreography.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CertificateExchangeFlowTest {

    private final JsonMapper mapper = new JsonMapper();

    @Mock
    private MembershipHubClient hub;
    @Mock
    private ManagementApiClient management;
    @Mock
    private CertoClient certo;
    @Mock
    private VerificationParticipantService participantService;

    private CertificateExchangeFlow flow;
    private VerificationRun run;

    @BeforeEach
    void setUp() {
        flow = new CertificateExchangeFlow(hub, management, certo, participantService, new ChecklistEvaluator(),
                TestFixtures.props(Map.of("events.onboarding.started", 1)));
        run = new VerificationRun("r1", "Participant r1", "put-r1", "BPNLPUT000000001", "DEPUT0001");
    }

    /** Stubs the whole happy choreography; individual tests break the link they exercise. */
    private void happyStubs() {
        when(participantService.ensure())
                .thenReturn(new VerificationParticipant("vp-1", "VP", "BPNLVERIFY000001", "did:web:vp", "pctx-vp"));
        when(hub.onboard("Participant r1", "put-r1", "BPNLPUT000000001", "DEPUT0001"))
                .thenReturn(TestFixtures.membership("put-ext", "SUBMITTED", null, null, null));
        when(hub.awaitProvisioned("put-ext"))
                .thenReturn(TestFixtures.membership("put-ext", "PROVISIONED", "did:web:put", "pctx-put", "proc-1"));
        when(management.awaitCatalogOffer(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(new ManagementApiClient.CatalogOffer(
                        mapper.createObjectNode().put("@id", "offer-1"), mapper.createArrayNode()));
        when(management.startNegotiation(anyString(), anyString(), anyString(), anyString(), any()))
                .thenReturn("neg-1");
        when(management.awaitState(contains("contractnegotiations"), any(), any()))
                .thenReturn(mapper.createObjectNode().put("state", "FINALIZED").put("contractAgreementId", "agr-1"));
        when(management.awaitState(contains("transferprocesses"), any(), any()))
                .thenReturn(mapper.createObjectNode().put("state", "STARTED"));
        // pull flow is established first, push flow second
        when(management.startTransfer(anyString(), eq("agr-1"), anyString(), eq("HttpData-PULL")))
                .thenReturn("flow-pull", "flow-push");
        when(certo.addDocument(eq("pctx-put"), eq("application/pdf"), any())).thenReturn("doc-1");
        when(certo.addCertificate(eq("pctx-put"), eq("BPNLPUT000000001"), eq("doc-1"), anyString()))
                .thenReturn("cert-1");
        when(certo.publish("pctx-put", "cert-1", "BPNLVERIFY000001", "did:web:vp", "flow-push"))
                .thenReturn("ex-1");
        when(certo.retrieve("pctx-vp", "ex-1", "flow-pull")).thenReturn(retrieved());
        when(certo.getExchange("pctx-put", "ex-1")).thenReturn(mapper.createObjectNode()
                .put("fulfillmentStatus", "FULFILLED").put("acceptanceStatus", "ACCEPTED"));
        when(hub.eventlog("proc-1")).thenReturn(Optional.of(rollup("events.onboarding.started")));
    }

    @Test
    void happyPath_succeedsWithEveryStepOkAndTheChecklistSatisfied() {
        happyStubs();

        flow.execute(run);

        var snapshot = run.snapshot();
        assertEquals(RunState.SUCCEEDED, snapshot.state());
        snapshot.steps().forEach(step -> assertEquals(StepStatus.OK, step.status(), step.step().name()));
        assertTrue(snapshot.checklist().stream().allMatch(item -> item.satisfied()));
        assertEquals("pctx-put", snapshot.participant().participantContextId());
        // the verdict travels over the PULL flow, from the VP side
        verify(certo).accept("pctx-vp", "ex-1", "ACCEPTED", "flow-pull");
    }

    @Test
    void provisioningFailure_failsFastAndSkipsTheRest() {
        happyStubs();
        when(hub.awaitProvisioned("put-ext")).thenThrow(new VerificationException("membership put-ext ended as REJECTED"));

        flow.execute(run);

        var snapshot = run.snapshot();
        assertEquals(RunState.FAILED, snapshot.state());
        assertEquals(RunStep.AWAIT_PROVISIONED, snapshot.failedStep());
        assertTrue(snapshot.failureReason().contains("REJECTED"));
        assertEquals(StepStatus.OK, statusOf(snapshot, RunStep.ONBOARD_PARTICIPANT));
        assertEquals(StepStatus.FAILED, statusOf(snapshot, RunStep.AWAIT_PROVISIONED));
        assertEquals(StepStatus.SKIPPED, statusOf(snapshot, RunStep.SEED_PROVIDER_OFFER));
        assertEquals(StepStatus.SKIPPED, statusOf(snapshot, RunStep.EVALUATE_EVENTS));
    }

    @Test
    void terminatedNegotiation_failsTheFlowEstablishment() {
        happyStubs();
        when(management.awaitState(contains("contractnegotiations"), any(), any()))
                .thenThrow(new VerificationException("negotiation reached TERMINATED"));

        flow.execute(run);

        var snapshot = run.snapshot();
        assertEquals(RunState.FAILED, snapshot.state());
        assertEquals(RunStep.ESTABLISH_PULL_FLOW, snapshot.failedStep());
        assertTrue(snapshot.failureReason().contains("TERMINATED"));
    }

    @Test
    void missingEvents_failTheVerdictButKeepTheChecklist() {
        happyStubs();
        when(hub.eventlog("proc-1")).thenReturn(Optional.of(rollup("events.something.else")));

        flow.execute(run);

        var snapshot = run.snapshot();
        assertEquals(RunState.FAILED, snapshot.state());
        assertEquals(RunStep.EVALUATE_EVENTS, snapshot.failedStep());
        assertTrue(snapshot.failureReason().contains("events.onboarding.started"));
        assertEquals(1, snapshot.checklist().size());
        assertEquals(0, snapshot.checklist().get(0).actualCount());
    }

    private static StepStatus statusOf(VerificationRun.Snapshot snapshot, RunStep step) {
        return snapshot.steps().stream()
                .filter(candidate -> candidate.step() == step)
                .findFirst().orElseThrow()
                .status();
    }

    private JsonNode retrieved() {
        var document = mapper.createObjectNode()
                .put("documentId", "doc-1")
                .put("mediaType", "application/pdf")
                .put("contentBase64", Base64.getEncoder().encodeToString(pdfBytes()));
        var documents = mapper.createArrayNode().add(document);
        var certificate = mapper.createObjectNode().put("certificateId", "cert-1");
        var retrieved = mapper.createObjectNode();
        retrieved.set("certificate", certificate);
        retrieved.set("documents", documents);
        return retrieved;
    }

    private JsonNode rollup(String... subjects) {
        var events = mapper.createArrayNode();
        for (var subject : subjects) {
            events.add(mapper.createObjectNode().put("subject", subject));
        }
        var rollup = mapper.createObjectNode();
        rollup.set("events", events);
        return rollup;
    }

    /** The same packaged PDF the flow uploads — the byte-compare must see identical content. */
    private static byte[] pdfBytes() {
        try (var stream = CertificateExchangeFlowTest.class.getResourceAsStream("/certificate-document.pdf")) {
            return stream.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
