package com.metaform.cxve.adapter.out.nats;

import com.metaform.cxve.application.OnboardingOrchestrator;
import io.nats.client.Connection;
import io.nats.client.JetStream;
import io.nats.client.JetStreamSubscription;
import io.nats.client.Message;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Subscribes to IdentityHub participant-context events on the {@code edc-events} JetStream stream
 * and re-drives the matching onboarding when its participant context has been created — the signal
 * that asynchronous wallet provisioning has progressed, letting the process leave the
 * IDENTITY_VERIFIED gate promptly instead of waiting for a later issuance event.
 *
 * <p>Same at-least-once semantics as {@link NatsIssuanceListener} (ack on success, nak for
 * redelivery, idempotent {@link OnboardingOrchestrator#advanceByHolder}), but on its own durable —
 * a JetStream consumer has a single subject filter, so participant-context events need a consumer
 * separate from the issuance one. Events for participant contexts this app did not provision (e.g.
 * the platform's issuer context) correlate to no onboarding and are acked without effect.
 */
@Component
@ConditionalOnProperty(prefix = "nats", name = "enabled", havingValue = "true")
public class NatsParticipantContextListener {

    private static final Logger log = LoggerFactory.getLogger(NatsParticipantContextListener.class);

    private final Connection connection;
    private final JetStream jetStream;
    private final ParticipantContextCloudEventParser parser;
    private final OnboardingOrchestrator orchestrator;
    private final NatsProperties properties;

    private JetStreamSubscription subscription;

    public NatsParticipantContextListener(Connection connection,
                                          JetStream jetStream,
                                          ParticipantContextCloudEventParser parser,
                                          OnboardingOrchestrator orchestrator,
                                          NatsProperties properties) {
        this.connection = connection;
        this.jetStream = jetStream;
        this.parser = parser;
        this.orchestrator = orchestrator;
        this.properties = properties;
    }

    @PostConstruct
    void subscribe() throws Exception {
        subscription = DurablePushSubscriptions.subscribe(connection, jetStream,
                properties.stream(),
                properties.participantContextDurableName(),
                properties.participantContextSubjectFilter(),
                this::onMessage);
    }

    void onMessage(Message message) {
        try {
            var event = parser.parseEnvelope(message.getData());
            if (parser.isParticipantContextCreated(event)) {
                parser.readData(event)
                        .ifPresent(
                                eventData -> {
                                    var id = eventData.correlationId();
                                    var pcId = eventData.participantContextId();
                                    var did = eventData.manifest().did();
                                    log.info("Participant with participantContextId={} created with DID={}. Correlation ID = {}", pcId, did, id);
                                });
            }
            message.ack();
        } catch (Exception e) {
            log.error("Failed to process participant context event, requesting redelivery", e);
            message.nak();
        }
    }

    @PreDestroy
    void unsubscribe() {
        if (subscription != null && subscription.isActive()) {
            subscription.unsubscribe();
        }
    }
}
