package com.metaform.dxonboarding.adapter.out.nats;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.metaform.dxonboarding.domain.model.onboarding.OnboardingCompleted;
import com.metaform.dxonboarding.domain.model.onboarding.OnboardingStarted;
import java.io.IOException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The envelope the compliance tracker reads: the same subjects as Catena-X's onboarding events (it
 * follows a participant from events.onboarding.started), but DECADE-X's own type and fields —
 * nothing of Catena-X's.
 */
class NatsOnboardingEventPublisherTest {

    private static final String DID = "did:web:identity.cxve.localhost:verification-participant-dx";

    private final NatsOnboardingEventPublisher publisher = new NatsOnboardingEventPublisher(null, "dx-onboarding-api-0");
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void theStartedEnvelope_isDecadeXsOwn() throws IOException {
        var envelope = publisher.envelope(NatsOnboardingEventPublisher.STARTED_TYPE, "req-1", DID,
                new OnboardingStarted("req-1", "ext-1", DID));

        assertThat(NatsOnboardingEventPublisher.STARTED_SUBJECT).isEqualTo("events.onboarding.started");
        assertThat(envelope.getType()).isEqualTo("org.decade-x.onboarding.OnboardingStarted.v1");
        assertThat(envelope.getSubject()).isEqualTo("req-1");
        assertThat(envelope.getSource().toString()).isEqualTo("dx-onboarding-api-0");
        assertThat(envelope.getExtension("participantdid")).isEqualTo(DID);
        assertThat(envelope.getExtensionNames()).doesNotContain("sourcebpn");
        var data = mapper.readTree(envelope.getData().toBytes());
        assertThat(data.path("processId").asText()).isEqualTo("req-1");
        assertThat(data.path("externalId").asText()).isEqualTo("ext-1");
        assertThat(data.path("did").asText()).isEqualTo(DID);
        assertThat(data.has("bpn")).isFalse();
    }

    @Test
    void theCompletedEnvelope_carriesTheOutcomeAndTheDecadeXId() throws IOException {
        var envelope = publisher.envelope(NatsOnboardingEventPublisher.COMPLETED_TYPE, "req-1", DID,
                new OnboardingCompleted("req-1", "ext-1", DID, "DX-99999999", OnboardingCompleted.State.COMPLETED, null));

        assertThat(NatsOnboardingEventPublisher.COMPLETED_SUBJECT).isEqualTo("events.onboarding.completed");
        assertThat(envelope.getType()).isEqualTo("org.decade-x.onboarding.OnboardingCompleted.v1");
        var data = mapper.readTree(envelope.getData().toBytes());
        assertThat(data.path("state").asText()).isEqualTo("COMPLETED");
        assertThat(data.path("decadeXId").asText()).isEqualTo("DX-99999999");
    }
}
