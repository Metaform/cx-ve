package com.metaform.cxve.verification.application;

import com.metaform.cxve.verification.adapter.out.certo.CertoClient;
import com.metaform.cxve.verification.adapter.out.did.DidWebResolver;
import com.metaform.cxve.verification.adapter.out.hub.MembershipHubClient;
import com.metaform.cxve.verification.adapter.out.management.ManagementApiClient;
import com.metaform.cxve.verification.domain.model.DidDocument;
import com.metaform.cxve.verification.domain.model.RunState;
import com.metaform.cxve.verification.domain.model.RunStep;
import com.metaform.cxve.verification.domain.model.StepStatus;
import com.metaform.cxve.verification.domain.model.VerificationParticipant;
import com.metaform.cxve.verification.domain.model.VerificationRun;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The external run against mocked clients. What these assert is mostly what the run does NOT do:
 * a third-party participant's side is never driven from here, so the mistake to guard against is
 * this environment quietly doing the SUT's work and reporting a pass for a system it never
 * exercised.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ExternalCertificateExchangeFlowTest {

    private static final String SUT_DID = "did:web:sut.example.com";
    private static final String SUT_DSP = "http://sut.example.com/api/dsp/tenant-9/cx-neptune";

    private final JsonMapper mapper = new JsonMapper();

    @Mock
    private MembershipHubClient hub;
    @Mock
    private ManagementApiClient management;
    @Mock
    private CertoClient certo;
    @Mock
    private DidWebResolver didResolver;
    @Mock
    private VerificationParticipantService participantService;

    private ExternalCertificateExchangeFlow flow;
    private VerificationRun run;

    @BeforeEach
    void setUp() {
        var properties = TestFixtures.props(Map.of("events.onboarding.started", 1));
        var support = new RunFlowSupport(management, hub, new ChecklistEvaluator(), properties);
        flow = new ExternalCertificateExchangeFlow(hub, certo, didResolver, participantService, support, properties);
        run = new VerificationRun("r1", "SUT GmbH", "sut", "BPNLSUT000000001", "DESUT0001",
                SUT_DID, RunStep.EXTERNAL);
    }

    private void happyStubs() {
        when(participantService.ensure())
                .thenReturn(new VerificationParticipant("vp-1", "VP", "BPNLVERIFY000001", "did:web:vp", "pctx-vp"));
        when(didResolver.resolve(SUT_DID))
                .thenReturn(new DidDocument(SUT_DID, SUT_DSP, "http://sut.example.com/api/credentials"));
        when(hub.findByDid(SUT_DID)).thenReturn(List.of());
        when(hub.onboard("SUT GmbH", "sut", "BPNLSUT000000001", "DESUT0001", SUT_DID))
                .thenReturn(TestFixtures.externalMembership("sut-ext", "SUBMITTED", SUT_DID, null));
        when(hub.awaitCredentialsOffered("sut-ext"))
                .thenReturn(TestFixtures.externalMembership("sut-ext", "CREDENTIALS_OFFERED", SUT_DID, "proc-1"));
        when(hub.eventlog("proc-1"))
                .thenReturn(Optional.of(rollup("events.issuance.credential.delivered")));
        when(management.awaitCatalogOffer(anyString(), anyString(), anyString(), anyString(), any()))
                .thenReturn(new ManagementApiClient.CatalogOffer(
                        mapper.createObjectNode().put("@id", "offer-1"), mapper.createArrayNode()));
        when(management.startNegotiation(anyString(), anyString(), anyString(), anyString(), any()))
                .thenReturn("neg-1");
        when(management.awaitState(contains("contractnegotiations"), any(), any()))
                .thenReturn(mapper.createObjectNode().put("state", "FINALIZED").put("contractAgreementId", "agr-1"));
        when(management.awaitState(contains("transferprocesses"), any(), any()))
                .thenReturn(mapper.createObjectNode().put("state", "STARTED"));
        when(management.startTransfer(anyString(), eq("agr-1"), anyString(), eq("HttpData-PULL")))
                .thenReturn("flow-pull");
        when(certo.consumerExchanges("pctx-vp", true)).thenReturn(exchangePage("ex-sut", null));
        when(certo.consumerExchanges("pctx-vp", false)).thenReturn(exchangePage("ex-sut", "ACCEPTED"));
        when(certo.retrieve("pctx-vp", "ex-sut", "flow-pull")).thenReturn(retrieved());
    }

    @Test
    void happyPath_verifiesTheSutWithoutDrivingAnyOfItsSide() {
        happyStubs();

        flow.execute(run);

        var snapshot = run.snapshot();
        assertThat(snapshot.state()).isEqualTo(RunState.SUCCEEDED);
        assertThat(snapshot.steps()).allSatisfy(step ->
                assertThat(step.status()).as(step.step().name()).isEqualTo(StepStatus.OK));
        // The steps of a managed run that have no counterpart here are not in the ledger at all,
        // rather than sitting PENDING forever.
        assertThat(snapshot.steps()).extracting(step -> step.step())
                .doesNotContain(RunStep.AWAIT_PROVISIONED, RunStep.SEED_PROVIDER_OFFER,
                        RunStep.ESTABLISH_PUSH_FLOW, RunStep.PUBLISH_CERTIFICATE, RunStep.AWAIT_CERTO_CONTEXT);
        assertThat(snapshot.participant().externallyHosted()).isTrue();
        assertThat(snapshot.participant().did()).isEqualTo(SUT_DID);
        assertThat(snapshot.participant().participantContextId()).isNull();

        // Nothing was published, seeded or provisioned on the SUT's behalf — the certificate came
        // from the SUT, and the verdict went back over the pull flow this environment opened.
        verify(certo, never()).addDocument(anyString(), anyString(), any());
        verify(certo, never()).addCertificate(anyString(), anyString(), anyString(), anyString());
        verify(certo, never()).publish(anyString(), anyString(), anyString(), anyString(), anyString());
        verify(management, never()).createAssetIdempotent(anyString(), anyString(), anyString());
        verify(certo).accept("pctx-vp", "ex-sut", "ACCEPTED", "flow-pull");
    }

    @Test
    void theSutsOwnDspEndpointIsDialled_notAnAddressSynthesizedFromThisEnvironment() {
        happyStubs();

        flow.execute(run);

        // The counterparty address comes from the SUT's DID document; a synthesized one would
        // point back into this cluster and verify the wrong system.
        verify(management).awaitCatalogOffer(eq("pctx-vp"), eq(SUT_DSP), eq(SUT_DID), eq("ccm-api"), any());
        verify(management).startNegotiation(eq("pctx-vp"), eq(SUT_DSP), eq(SUT_DID), eq("ccm-api"), any());
    }

    @Test
    void anUnresolvableDid_failsBeforeAnythingIsOnboarded() {
        happyStubs();
        when(didResolver.resolve(SUT_DID))
                .thenThrow(new VerificationException("DID document of %s is unreachable".formatted(SUT_DID)));

        flow.execute(run);

        var snapshot = run.snapshot();
        assertThat(snapshot.state()).isEqualTo(RunState.FAILED);
        assertThat(snapshot.failedStep()).isEqualTo(RunStep.RESOLVE_DID);
        assertThat(snapshot.failureReason()).contains("unreachable");
        verify(hub, never()).onboard(anyString(), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void anExistingMembershipIsAdopted_becauseReOnboardingTheSameDidWouldBeDeclined() {
        happyStubs();
        when(hub.findByDid(SUT_DID)).thenReturn(List.of(
                TestFixtures.externalMembership("sut-ext", "CREDENTIALS_OFFERED", SUT_DID, "proc-1")));

        flow.execute(run);

        assertThat(run.snapshot().state()).isEqualTo(RunState.SUCCEEDED);
        assertThat(run.snapshot().participant().externalId()).isEqualTo("sut-ext");
        verify(hub, never()).onboard(anyString(), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void aFailedMembershipIsNotAdopted() {
        happyStubs();
        when(hub.findByDid(SUT_DID)).thenReturn(List.of(
                TestFixtures.externalMembership("dead-ext", "FAILED", SUT_DID, null)));

        flow.execute(run);

        // A dead attempt retires nothing, so a fresh registration is the right move.
        verify(hub).onboard("SUT GmbH", "sut", "BPNLSUT000000001", "DESUT0001", SUT_DID);
    }

    @Test
    void credentialsNeverDelivered_failsBeforeTheExchangeIsAttempted() {
        happyStubs();
        // the SUT never requested its credentials: the ledger stays without a delivery
        when(hub.eventlog("proc-1")).thenReturn(Optional.of(rollup("events.onboarding.completed")));

        flow.execute(run);

        var snapshot = run.snapshot();
        assertThat(snapshot.state()).isEqualTo(RunState.FAILED);
        assertThat(snapshot.failedStep()).isEqualTo(RunStep.AWAIT_CREDENTIALS);
        assertThat(snapshot.checklist()).anySatisfy(item -> {
            assertThat(item.subject()).isEqualTo("events.issuance.credential.delivered");
            assertThat(item.satisfied()).isFalse();
        });
        verify(management, never()).awaitCatalogOffer(anyString(), anyString(), anyString(), anyString(), any());
    }

    @Test
    void severalExchangesAwaitingAcceptance_isReportedRatherThanGuessed() {
        happyStubs();
        var page = mapper.createObjectNode();
        var items = page.putArray("items");
        items.add(mapper.createObjectNode().put("exchangeId", "ex-a"));
        items.add(mapper.createObjectNode().put("exchangeId", "ex-b"));
        when(certo.consumerExchanges("pctx-vp", true)).thenReturn(page);

        flow.execute(run);

        var snapshot = run.snapshot();
        assertThat(snapshot.state()).isEqualTo(RunState.FAILED);
        assertThat(snapshot.failedStep()).isEqualTo(RunStep.AWAIT_PUBLISHED_CERTIFICATE);
        // Picking one would silently verify an unrelated push.
        assertThat(snapshot.failureReason()).contains("cannot tell which one");
    }

    @Test
    void anEmptyDocument_failsTheVerification() {
        happyStubs();
        var empty = mapper.createObjectNode();
        empty.putObject("certificate").put("certificateId", "cert-sut");
        empty.putArray("documents").addObject().put("documentId", "doc-sut")
                .put("mediaType", "application/pdf").put("contentBase64", "");
        when(certo.retrieve("pctx-vp", "ex-sut", "flow-pull")).thenReturn(empty);

        flow.execute(run);

        assertThat(run.snapshot().failedStep()).isEqualTo(RunStep.RETRIEVE_AND_VERIFY);
        assertThat(run.snapshot().failureReason()).contains("no content");
    }

    private JsonNode exchangePage(String exchangeId, String acceptanceStatus) {
        var page = mapper.createObjectNode();
        var item = page.putArray("items").addObject()
                .put("exchangeId", exchangeId)
                .put("certificateId", "cert-sut")
                .put("fulfillmentStatus", "FULFILLED");
        if (acceptanceStatus != null) {
            item.put("acceptanceStatus", acceptanceStatus);
        }
        return page;
    }

    private JsonNode retrieved() {
        var node = mapper.createObjectNode();
        node.putObject("certificate").put("certificateId", "cert-sut");
        node.putArray("documents").addObject()
                .put("documentId", "doc-sut")
                .put("mediaType", "application/pdf")
                .put("contentBase64", Base64.getEncoder()
                        .encodeToString("a certificate this environment never authored".getBytes(StandardCharsets.UTF_8)));
        return node;
    }

    private JsonNode rollup(String... subjects) {
        var node = mapper.createObjectNode();
        var events = node.putArray("events");
        for (var subject : subjects) {
            events.addObject().put("subject", subject);
        }
        return node;
    }
}
