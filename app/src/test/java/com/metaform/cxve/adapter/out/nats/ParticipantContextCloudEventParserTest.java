package com.metaform.cxve.adapter.out.nats;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.cloudevents.core.builder.CloudEventBuilder;
import io.cloudevents.jackson.JsonFormat;
import java.net.URI;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class ParticipantContextCloudEventParserTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ParticipantContextCloudEventParser parser = new ParticipantContextCloudEventParser();

    /** Mirrors how the EDC events-nats bridge serializes: type = payload FQCN, data = payload JSON. */
    private byte[] cloudEvent(String type, ParticipantContextEventData data) throws Exception {
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
    void parsesCreatedEventAndCorrelatesByManifestDid() throws Exception {
        var bytes = cloudEvent(
                "org.eclipse.edc.identityhub.spi.participantcontext.events.ParticipantContextCreated",
                new ParticipantContextEventData("ctx-1", new ParticipantContextEventData.Manifest("did:web:acme")));

        var event = parser.parseEnvelope(bytes);

        assertThat(parser.isParticipantContextCreated(event)).isTrue();
        assertThat(parser.readData(event)).hasValueSatisfying(data ->
                assertThat(data.correlationId()).isEqualTo("did:web:acme"));
    }

    @Test
    void fallsBackToParticipantContextIdWithoutManifestDid() throws Exception {
        var bytes = cloudEvent(
                "org.eclipse.edc.identityhub.spi.participantcontext.events.ParticipantContextCreated",
                new ParticipantContextEventData("ctx-1", null));

        var event = parser.parseEnvelope(bytes);

        assertThat(parser.readData(event)).hasValueSatisfying(data ->
                assertThat(data.correlationId()).isEqualTo("ctx-1"));
    }

    @Test
    void doesNotMatchOtherParticipantContextEvents() throws Exception {
        var bytes = cloudEvent(
                "org.eclipse.edc.identityhub.spi.participantcontext.events.ParticipantContextUpdated",
                new ParticipantContextEventData("ctx-1", new ParticipantContextEventData.Manifest("did:web:acme")));

        var event = parser.parseEnvelope(bytes);

        assertThat(parser.isParticipantContextCreated(event)).isFalse();
    }
}
