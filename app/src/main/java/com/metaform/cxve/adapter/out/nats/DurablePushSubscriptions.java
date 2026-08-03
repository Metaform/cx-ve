package com.metaform.cxve.adapter.out.nats;

import io.nats.client.Connection;
import io.nats.client.JetStream;
import io.nats.client.JetStreamApiException;
import io.nats.client.JetStreamSubscription;
import io.nats.client.MessageHandler;
import io.nats.client.PushSubscribeOptions;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Joins a durable JetStream push consumer through a deliver group, the subscription mode shared by
 * all listeners in this package.
 *
 * <p>The {@code edc-events} stream is memory-backed with interest retention, so a <b>durable</b>
 * binding is what keeps events from being dropped while this service is briefly disconnected, and
 * lets JetStream redeliver anything left unacked. Subscriptions are created with {@code autoAck}
 * off — handlers ack explicitly after processing (and nak on failure to trigger redelivery).
 */
final class DurablePushSubscriptions {

    private static final Logger log = LoggerFactory.getLogger(DurablePushSubscriptions.class);

    private DurablePushSubscriptions() {
    }

    static JetStreamSubscription subscribe(Connection connection,
                                           JetStream jetStream,
                                           String stream,
                                           String durable,
                                           String subjectFilter,
                                           MessageHandler handler) throws IOException, JetStreamApiException {
        // The deliver group (named after the durable) is what allows several app replicas to share
        // the durable: each member joins the same server-side consumer and messages are
        // load-balanced across them. A group-less durable push consumer admits exactly ONE bound
        // subscription — a second replica would fail with [SUB-90012] "Consumer is already bound
        // to a subscription".
        var group = durable;
        var options = PushSubscribeOptions.builder()
                .stream(stream)
                .durable(durable)
                .deliverGroup(group)
                .build();
        var dispatcher = connection.createDispatcher();
        JetStreamSubscription subscription;
        try {
            subscription = jetStream.subscribe(subjectFilter, group, dispatcher, handler, false, options);
        } catch (IllegalArgumentException | JetStreamApiException e) {
            // A durable created by an older app version with a different configuration (no deliver
            // group, different subject filter) cannot be joined — drop it and recreate. Safe:
            // delivery is at-least-once, the stream redelivers unacked messages, and the handlers
            // are idempotent. (An old-version replica still bound during a rolling update loses its
            // subscription, but it is terminating anyway.)
            log.warn("Subscribing durable '{}' failed ({}); recreating it with deliver group '{}'",
                    durable, e.getMessage(), group);
            connection.jetStreamManagement().deleteConsumer(stream, durable);
            subscription = jetStream.subscribe(subjectFilter, group, dispatcher, handler, false, options);
        }
        log.info("Subscribed to '{}' on stream '{}' as durable '{}' in deliver group '{}'",
                subjectFilter, stream, durable, group);
        return subscription;
    }
}
