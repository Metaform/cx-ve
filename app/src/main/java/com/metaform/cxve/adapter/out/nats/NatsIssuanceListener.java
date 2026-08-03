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
 * Subscribes to IdentityHub issuance events on the {@code edc-events} JetStream stream and drives the
 * matching onboarding forward when credentials are delivered.
 *
 * <p>Delivery is at-least-once (see {@link DurablePushSubscriptions} for the durable/deliver-group
 * mechanics) — {@link OnboardingOrchestrator#advanceByHolder} is idempotent, so a redelivered event
 * for an already-completed onboarding is harmless. Messages are acked on success and nak'd on
 * failure to trigger redelivery.
 */
@Component
@ConditionalOnProperty(prefix = "nats", name = "enabled", havingValue = "true")
public class NatsIssuanceListener {

    private static final Logger log = LoggerFactory.getLogger(NatsIssuanceListener.class);

    private final Connection connection;
    private final JetStream jetStream;
    private final IssuanceCloudEventParser parser;
    private final OnboardingOrchestrator orchestrator;
    private final NatsProperties properties;

    private JetStreamSubscription subscription;

    public NatsIssuanceListener(Connection connection,
                                JetStream jetStream,
                                IssuanceCloudEventParser parser,
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
                properties.durableName(),
                properties.subjectFilter(),
                this::onMessage);
    }

    void onMessage(Message message) {
        try {
            var event = parser.parseEnvelope(message.getData());
            if (parser.isCredentialDelivered(event)) {
                var issuanceEventData = parser.readData(event);
                issuanceEventData
                        .ifPresentOrElse(
                                data -> {
                                    log.info("Credential delivered event {} for holder {}, verifying...", event.getId(), data.holderId());
                                    orchestrator.advanceByHolder(data.holderId());
                                },
                                () -> log.warn("Credential delivered event {} carried no holderId", event.getId()));
            }
            message.ack();
        } catch (Exception e) {
            log.error("Failed to process issuance event, requesting redelivery", e);
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
