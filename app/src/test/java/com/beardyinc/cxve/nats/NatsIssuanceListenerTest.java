package com.beardyinc.cxve.nats;

import com.beardyinc.cxve.infrastructure.nats.IssuanceCloudEventParser;
import com.beardyinc.cxve.infrastructure.nats.IssuanceEventData;
import com.beardyinc.cxve.infrastructure.nats.NatsIssuanceListener;
import com.beardyinc.cxve.infrastructure.nats.NatsProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.beardyinc.cxve.onboarding.OnboardingOrchestrator;
import io.cloudevents.core.builder.CloudEventBuilder;
import io.cloudevents.jackson.JsonFormat;
import io.nats.client.Connection;
import io.nats.client.JetStream;
import io.nats.client.Message;
import org.junit.jupiter.api.Test;

import java.net.URI;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class NatsIssuanceListenerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final IssuanceCloudEventParser parser = new IssuanceCloudEventParser();
    private final OnboardingOrchestrator orchestrator = mock(OnboardingOrchestrator.class);

    private final NatsIssuanceListener listener = new NatsIssuanceListener(
            mock(Connection.class), mock(JetStream.class), parser, orchestrator,
            new NatsProperties(true, null, null, null, null, null));

    private byte[] deliveredEvent(String holderId) throws Exception {
        var event = CloudEventBuilder.v1()
                .withId("evt-1")
                .withSource(URI.create("//identityhub"))
                .withType("org.eclipse.edc.issuerservice.issuance.events.CredentialDelivered")
                .withDataContentType("application/json")
                .withData(objectMapper.writeValueAsBytes(
                        new IssuanceEventData(holderId, "ctx-1", "holder-proc-1", "issuance-1")))
                .build();
        return new JsonFormat().serialize(event);
    }

    @Test
    void deliveredEvent_correlatesToOrchestratorAndAcks() throws Exception {
        var message = mock(Message.class);
        when(message.getData()).thenReturn(deliveredEvent("did:web:acme"));

        listener.onMessage(message);

        verify(orchestrator).advanceByHolder("did:web:acme");
        verify(message).ack();
    }

    @Test
    void malformedMessage_isNakedForRedelivery() {
        var message = mock(Message.class);
        when(message.getData()).thenReturn("not a cloudevent".getBytes());

        listener.onMessage(message);

        verify(message).nak();
    }
}
