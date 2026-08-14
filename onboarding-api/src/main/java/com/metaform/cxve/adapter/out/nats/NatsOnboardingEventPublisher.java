package com.metaform.cxve.adapter.out.nats;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.metaform.cxve.domain.model.OnboardingCompleted;
import com.metaform.cxve.domain.model.OnboardingStarted;
import com.metaform.cxve.domain.port.OnboardingEventPublisher;
import io.cloudevents.CloudEvent;
import io.cloudevents.core.builder.CloudEventBuilder;
import io.cloudevents.jackson.JsonFormat;
import io.nats.client.JetStream;
import io.nats.client.impl.Headers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Publishes onboarding lifecycle events onto the platform's shared {@code edc-events} JetStream
 * stream as structured-mode CloudEvents.
 *
 * <p>Deliberately symmetric with what this app already CONSUMES from that stream (see
 * {@link IssuanceCloudEventParser}): same JSON structured mode, same content type, and the envelope
 * is written with the same CloudEvents SDK that parses the inbound ones — so a subscriber needs no
 * special handling to read both.
 *
 * <p>Failures are logged, never rethrown. The onboarding has already been accepted and is running
 * by the time an event is raised; failing the caller because a broker was unreachable would report
 * work as failed that is in fact under way.
 */
public class NatsOnboardingEventPublisher implements OnboardingEventPublisher {

    /** Subject for the start of an onboarding. Under the stream's {@code events.>} capture. */
    static final String ONBOARDING_STARTED_SUBJECT = "events.onboarding.started";
    /** Reverse-DNS event type, matching the CX-0000 §2.3 convention used across the platform. */
    static final String ONBOARDING_STARTED_TYPE = "org.catena-x.onboarding.OnboardingStarted.v1";
    /** Subject for a successfully completed onboarding. */
    static final String ONBOARDING_COMPLETED_SUBJECT = "events.onboarding.completed";
    static final String ONBOARDING_COMPLETED_TYPE = "org.catena-x.onboarding.OnboardingCompleted.v1";

    private static final Logger log = LoggerFactory.getLogger(NatsOnboardingEventPublisher.class);

    private final JetStream jetStream;
    private final JsonFormat cloudEventFormat = new JsonFormat();
    // The event 'data' is a self-contained JSON object; a plain mapper keeps this component
    // independent of the web layer's JSON configuration (same reasoning as IssuanceCloudEventParser).
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final URI source;

    public NatsOnboardingEventPublisher(JetStream jetStream, String source) {
        this.jetStream = jetStream;
        this.source = URI.create(source);
    }

    @Override
    public void onboardingStarted(OnboardingStarted event) {
        publish(ONBOARDING_STARTED_SUBJECT, ONBOARDING_STARTED_TYPE,
                event.processId(), event.bpn(), event.did(), event);
    }

    @Override
    public void onboardingCompleted(OnboardingCompleted event) {
        publish(ONBOARDING_COMPLETED_SUBJECT, ONBOARDING_COMPLETED_TYPE,
                event.processId(), event.bpn(), event.did(), event);
    }

    private void publish(String subject, String type, String processId, String bpn, String did, Object data) {
        try {
            var envelope = envelope(type, processId, bpn, did, data);
            jetStream.publish(subject, headers(), cloudEventFormat.serialize(envelope));
            log.debug("Published {} for onboarding {} (bpn={}, did={})", subject, processId, bpn, did);
        } catch (Exception e) {
            log.error("Failed to publish {} for onboarding {}", subject, processId, e);
        }
    }

    /**
     * Builds the CloudEvents envelope. The BPN and DID are carried as extensions as well as in the
     * data, so a subscriber can filter on them without decoding the payload — the same reason the
     * platform's events carry {@code sourcebpn} (CX-0000 §2.1.2).
     */
    // Package-private so the envelope contract can be asserted without standing up a broker.
    CloudEvent envelope(String type, String processId, String bpn, String did, Object data) throws IOException {
        var builder = CloudEventBuilder.v1()
                .withId(UUID.randomUUID().toString())
                .withType(type)
                .withSource(source)
                .withSubject(processId)
                .withTime(OffsetDateTime.now())
                .withDataContentType("application/json")
                .withData(objectMapper.writeValueAsBytes(data));
        // Extensions are set only when present: the CloudEvents builder rejects a null value, and a
        // missing identity must not cost the whole event — the payload still carries the full state.
        if (bpn != null) {
            builder.withExtension("sourcebpn", bpn);
        }
        if (did != null) {
            builder.withExtension("participantdid", did);
        }
        return builder.build();
    }

    private static Headers headers() {
        return new Headers().add("Content-Type", JsonFormat.CONTENT_TYPE);
    }
}
