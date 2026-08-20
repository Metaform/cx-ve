package com.metaform.cxve.adapter.out.nats;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.metaform.cxve.domain.model.OnboardingCompleted;
import com.metaform.cxve.domain.model.OnboardingStarted;
import com.metaform.cxve.domain.model.OnboardingState;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Asserts the wire contract of the published envelope — the part a subscriber depends on and that
 * cannot be changed without breaking it.
 */
class NatsOnboardingEventPublisherTest {

    private final ObjectMapper mapper = new ObjectMapper();
    // JetStream is only touched by the publish path, not by envelope construction.
    private final NatsOnboardingEventPublisher publisher =
            new NatsOnboardingEventPublisher(null, "onboarding-api-7d56645cc7-2bzkl");

    @Test
    void envelopeCarriesTheIdentitiesInBothTheExtensionsAndTheData() throws Exception {
        var event = new OnboardingStarted("proc-1", "ext-123", "BPNL0000000001AB", "did:web:identity.test:acme");
        var envelope = publisher.envelope(NatsOnboardingEventPublisher.ONBOARDING_STARTED_TYPE,
                event.processId(), event.bpn(), event.did(), event);

        assertThat(envelope.getSpecVersion().toString()).isEqualTo("1.0");
        assertThat(envelope.getType()).isEqualTo("org.catena-x.onboarding.OnboardingStarted.v1");
        // source names the emitting application, as in every other platform producer.
        assertThat(envelope.getSource()).hasToString("onboarding-api-7d56645cc7-2bzkl");
        assertThat(envelope.getSubject()).isEqualTo("proc-1");
        assertThat(envelope.getTime()).isNotNull();

        // Extensions let a subscriber filter without decoding the payload.
        assertThat(envelope.getExtension("sourcebpn")).isEqualTo("BPNL0000000001AB");
        assertThat(envelope.getExtension("participantdid")).isEqualTo("did:web:identity.test:acme");

        var data = mapper.readTree(envelope.getData().toBytes());
        assertThat(data.path("processId").asText()).isEqualTo("proc-1");
        assertThat(data.path("externalId").asText()).isEqualTo("ext-123");
        assertThat(data.path("bpn").asText()).isEqualTo("BPNL0000000001AB");
        assertThat(data.path("did").asText()).isEqualTo("did:web:identity.test:acme");
    }

    @Test
    void completedEnvelopeCarriesTheRegisteredIdentities() throws Exception {
        var event = new OnboardingCompleted("proc-1", "ext-123", "BPNL0000000001AB",
                "did:web:identity.test:acme", OnboardingState.COMPLETED, null);
        var envelope = publisher.envelope(NatsOnboardingEventPublisher.ONBOARDING_COMPLETED_TYPE,
                event.processId(), event.bpn(), event.did(), event);

        assertThat(envelope.getType()).isEqualTo("org.catena-x.onboarding.OnboardingCompleted.v1");
        // Same subject as the started event, so both legs of one onboarding correlate.
        assertThat(envelope.getSubject()).isEqualTo("proc-1");
        assertThat(envelope.getExtension("sourcebpn")).isEqualTo("BPNL0000000001AB");

        var data = mapper.readTree(envelope.getData().toBytes());
        assertThat(data.path("did").asText()).isEqualTo("did:web:identity.test:acme");
        // The terminal state is on the wire: the subject is shared with the failure outcomes, so it
        // is what tells a subscriber which of them it received.
        assertThat(data.path("state").asText()).isEqualTo("COMPLETED");
    }

    @Test
    void aMissingIdentityStillProducesAnEvent() throws Exception {
        // The CloudEvents builder rejects a null extension value; losing the whole event over one
        // absent field would be worse than shipping it with the field only in the payload.
        var envelope = publisher.envelope(NatsOnboardingEventPublisher.ONBOARDING_COMPLETED_TYPE,
                "proc-3", null, null, new OnboardingCompleted("proc-3", "ext-3", null, null,
                        OnboardingState.FAILED, "holder registration timed out"));

        assertThat(envelope.getExtension("sourcebpn")).isNull();
        assertThat(envelope.getExtension("participantdid")).isNull();
        var data = mapper.readTree(envelope.getData().toBytes());
        assertThat(data.path("processId").asText()).isEqualTo("proc-3");
        // A failed onboarding may never get its identities assigned, so the outcome fields are the
        // only thing a subscriber can act on — they must survive even when the identities are absent.
        assertThat(data.path("state").asText()).isEqualTo("FAILED");
        assertThat(data.path("failureMessage").asText()).isEqualTo("holder registration timed out");
    }

    @Test
    void theSubjectsAreTheAgreedOnes() {
        // Hard-coded rather than referenced symbolically: the subject IS the contract, so a change
        // to the constant should fail here rather than silently move every subscriber's target.
        assertThat(NatsOnboardingEventPublisher.ONBOARDING_STARTED_SUBJECT).isEqualTo("events.onboarding.started");
        assertThat(NatsOnboardingEventPublisher.ONBOARDING_COMPLETED_SUBJECT).isEqualTo("events.onboarding.completed");
    }

    @Test
    void aFailingBrokerDoesNotPropagate() {
        // The onboarding is already accepted and running by the time this fires; a broker problem
        // must not surface as a failed registration call. Null JetStream => NPE inside publish().
        publisher.onboardingStarted(new OnboardingStarted("proc-2", "ext-2", "BPNL2", "did:web:x"));
    }
}
