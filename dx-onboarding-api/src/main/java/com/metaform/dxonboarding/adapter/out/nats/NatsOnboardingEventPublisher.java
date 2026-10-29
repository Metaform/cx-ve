package com.metaform.dxonboarding.adapter.out.nats;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.metaform.dxonboarding.domain.model.onboarding.OnboardingCompleted;
import com.metaform.dxonboarding.domain.model.onboarding.OnboardingStarted;
import com.metaform.dxonboarding.domain.port.OnboardingEventPublisher;
import io.cloudevents.CloudEvent;
import io.cloudevents.core.builder.CloudEventBuilder;
import io.cloudevents.jackson.JsonFormat;
import io.nats.client.JetStream;
import io.nats.client.impl.Headers;
import java.io.IOException;
import java.net.URI;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Publishes the onboarding lifecycle as structured-mode CloudEvents onto the platform's
 * {@code edc-events} stream — on the SAME subjects as the cx-onboarding-api, since the compliance
 * tracker follows a participant from {@code events.onboarding.started} whatever its dataspace, but
 * as DECADE-X's own events: their {@code type} is DECADE-X's, and they carry nothing of Catena-X's
 * (no BPN, no CX-0000 {@code sourcebpn} extension) — the DECADE-X-ID travels as {@code decadeXId}.
 * The CloudEvent {@code subject} is the onboarding request's id, the correlation key.
 */
public class NatsOnboardingEventPublisher implements OnboardingEventPublisher {

    static final String STARTED_SUBJECT = "events.onboarding.started";
    static final String STARTED_TYPE = "org.decade-x.onboarding.OnboardingStarted.v1";
    static final String COMPLETED_SUBJECT = "events.onboarding.completed";
    static final String COMPLETED_TYPE = "org.decade-x.onboarding.OnboardingCompleted.v1";

    private static final Logger log = LoggerFactory.getLogger(NatsOnboardingEventPublisher.class);

    private final JetStream jetStream;
    private final JsonFormat cloudEventFormat = new JsonFormat();
    // the event data is a self-contained JSON object, independent of the web layer's JSON setup
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final URI source;

    public NatsOnboardingEventPublisher(JetStream jetStream, String source) {
        this.jetStream = jetStream;
        this.source = URI.create(source);
    }

    @Override
    public void onboardingStarted(OnboardingStarted event) {
        publish(STARTED_SUBJECT, STARTED_TYPE, event.processId(), event.did(), event);
    }

    @Override
    public void onboardingCompleted(OnboardingCompleted event) {
        publish(COMPLETED_SUBJECT, COMPLETED_TYPE, event.processId(), event.did(), event);
    }

    private void publish(String subject, String type, String processId, String did, Object data) {
        try {
            jetStream.publish(subject, new Headers().add("Content-Type", JsonFormat.CONTENT_TYPE),
                    cloudEventFormat.serialize(envelope(type, processId, did, data)));
            log.debug("published {} for onboarding request {} ({})", subject, processId, did);
        } catch (Exception e) {
            log.error("Failed to publish {} for onboarding request {}", subject, processId, e);
        }
    }

    // package-private: the envelope contract is asserted without a broker
    CloudEvent envelope(String type, String processId, String did, Object data) throws IOException {
        var builder = CloudEventBuilder.v1()
                .withId(UUID.randomUUID().toString())
                .withType(type)
                .withSource(source)
                .withSubject(processId)
                .withTime(OffsetDateTime.now())
                .withDataContentType("application/json")
                .withData(objectMapper.writeValueAsBytes(data));
        if (did != null) {
            builder.withExtension("participantdid", did);
        }
        return builder.build();
    }
}
