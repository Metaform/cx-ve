package com.metaform.cxve.adapter.out.nats;

import com.metaform.cxve.application.OnboardingOrchestrator;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.cloudevents.core.builder.CloudEventBuilder;
import io.cloudevents.jackson.JsonFormat;
import io.nats.client.Connection;
import io.nats.client.Dispatcher;
import io.nats.client.JetStream;
import io.nats.client.JetStreamManagement;
import io.nats.client.JetStreamSubscription;
import io.nats.client.Message;
import io.nats.client.MessageHandler;
import io.nats.client.PushSubscribeOptions;
import java.net.URI;
import org.junit.jupiter.api.Test;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class NatsParticipantContextListenerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ParticipantContextCloudEventParser parser = new ParticipantContextCloudEventParser();
    private final OnboardingOrchestrator orchestrator = mock(OnboardingOrchestrator.class);

    private final NatsParticipantContextListener listener = new NatsParticipantContextListener(
            mock(Connection.class), mock(JetStream.class), parser, orchestrator,
            new NatsProperties(true, null, null, null, null, null, null));

    private byte[] participantContextEvent(String type, ParticipantContextEventData data) throws Exception {
        var event = CloudEventBuilder.v1()
                .withId("evt-1")
                .withSource(URI.create("//identityhub"))
                .withType(type)
                .withDataContentType("application/json")
                .withData(objectMapper.writeValueAsBytes(data))
                .build();
        return new JsonFormat().serialize(event);
    }

    @Test
    void createdEvent_isAcked() throws Exception {
        var message = mock(Message.class);
        when(message.getData()).thenReturn(participantContextEvent(
                "org.eclipse.edc.identityhub.spi.participantcontext.events.ParticipantContextCreated",
                new ParticipantContextEventData("ctx-1", new ParticipantContextEventData.Manifest("did:web:acme"))));

        listener.onMessage(message);

        verify(message).ack();
    }

    @Test
    void otherParticipantContextEvent_isAckedWithoutAdvancing() throws Exception {
        var message = mock(Message.class);
        when(message.getData()).thenReturn(participantContextEvent(
                "org.eclipse.edc.identityhub.spi.participantcontext.events.ParticipantContextDeleted",
                new ParticipantContextEventData("ctx-1", null)));

        listener.onMessage(message);

        verify(orchestrator, never()).advanceByHolder(any());
        verify(message).ack();
    }

    @Test
    void malformedMessage_isNakedForRedelivery() {
        var message = mock(Message.class);
        when(message.getData()).thenReturn("not a cloudevent".getBytes());

        listener.onMessage(message);

        verify(message).nak();
    }

    @Test
    void subscribe_joinsOwnDurableThroughDeliverGroup() throws Exception {
        var connection = mock(Connection.class);
        var jetStream = mock(JetStream.class);
        var dispatcher = mock(Dispatcher.class);
        when(connection.createDispatcher()).thenReturn(dispatcher);
        var l = new NatsParticipantContextListener(connection, jetStream, parser, orchestrator,
                new NatsProperties(true, null, null, null, null, null, null));

        l.subscribe();

        verify(jetStream).subscribe(eq("events.participantcontext.>"), eq("onboarding-api-participantcontext"), eq(dispatcher),
                any(MessageHandler.class), eq(false),
                argThat((PushSubscribeOptions o) -> "onboarding-api-participantcontext".equals(o.getDeliverGroup())
                        && "onboarding-api-participantcontext".equals(o.getDurable())
                        && "edc-events".equals(o.getStream())));
    }

    @Test
    void subscribe_recreatesUnjoinableDurable() throws Exception {
        var connection = mock(Connection.class);
        var jetStream = mock(JetStream.class);
        var jsm = mock(JetStreamManagement.class);
        when(connection.createDispatcher()).thenReturn(mock(Dispatcher.class));
        when(connection.jetStreamManagement()).thenReturn(jsm);
        when(jetStream.subscribe(any(), any(String.class), any(Dispatcher.class),
                any(MessageHandler.class), anyBoolean(), any(PushSubscribeOptions.class)))
                .thenThrow(new IllegalArgumentException("[SUB-90012] Consumer is already bound to a subscription."))
                .thenReturn(mock(JetStreamSubscription.class));
        var l = new NatsParticipantContextListener(connection, jetStream, parser, orchestrator,
                new NatsProperties(true, null, null, null, null, null, null));

        l.subscribe();

        verify(jsm).deleteConsumer("edc-events", "onboarding-api-participantcontext");
        verify(jetStream, times(2)).subscribe(any(), any(String.class), any(Dispatcher.class),
                any(MessageHandler.class), anyBoolean(), any(PushSubscribeOptions.class));
    }
}
