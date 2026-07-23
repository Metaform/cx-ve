package com.metaform.cxve.adapter.out.nats;

import com.metaform.cxve.application.OnboardingOrchestrator;
import io.nats.client.Connection;
import io.nats.client.Dispatcher;
import io.nats.client.JetStream;
import io.nats.client.JetStreamApiException;
import io.nats.client.JetStreamSubscription;
import io.nats.client.Message;
import io.nats.client.PushSubscribeOptions;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Subscribes to IdentityHub issuance events on the {@code edc-events} JetStream stream and drives the
 * matching onboarding forward when credentials are delivered.
 *
 * <p>Uses a <b>durable</b> consumer: the {@code edc-events} stream is memory-backed with interest
 * retention, so a durable binding is what keeps events from being dropped while this service is
 * briefly disconnected, and lets JetStream redeliver anything left unacked. Delivery is at-least-once
 * — {@link OnboardingOrchestrator#advanceByHolder} is idempotent, so a redelivered event for an
 * already-completed onboarding is harmless. Messages are acked on success and nak'd on failure to
 * trigger redelivery.
 *
 * <p>The durable is joined through a <b>deliver group</b> (named after the durable) so several app
 * replicas can consume concurrently, load-balancing the events across members.
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

    private Dispatcher dispatcher;
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
        // The deliver group (named after the durable) is what allows several app replicas to share
        // the durable: each member joins the same server-side consumer and messages are
        // load-balanced across them. A group-less durable push consumer admits exactly ONE bound
        // subscription — a second replica would fail with [SUB-90012] "Consumer is already bound
        // to a subscription".
        var group = properties.durableName();
        var options = PushSubscribeOptions.builder()
                .stream(properties.stream())
                .durable(properties.durableName())
                .deliverGroup(group)
                .build();
        dispatcher = connection.createDispatcher();
        try {
            // autoAck=false: we ack explicitly only after the event has been processed.
            subscription = jetStream.subscribe(properties.subjectFilter(), group, dispatcher, this::onMessage, false, options);
        } catch (IllegalArgumentException | JetStreamApiException e) {
            // A durable created by an older app version WITHOUT a deliver group cannot be joined —
            // drop it and recreate with the group. Safe: delivery is at-least-once, the stream
            // redelivers unacked messages, and advanceByHolder is idempotent. (An old-version
            // replica still bound during a rolling update loses its subscription, but it is
            // terminating anyway.)
            log.warn("Subscribing durable '{}' failed ({}); recreating it with deliver group '{}'",
                    properties.durableName(), e.getMessage(), group);
            connection.jetStreamManagement().deleteConsumer(properties.stream(), properties.durableName());
            subscription = jetStream.subscribe(properties.subjectFilter(), group, dispatcher, this::onMessage, false, options);
        }
        log.info("Subscribed to '{}' on stream '{}' as durable '{}' in deliver group '{}'",
                properties.subjectFilter(), properties.stream(), properties.durableName(), group);
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
