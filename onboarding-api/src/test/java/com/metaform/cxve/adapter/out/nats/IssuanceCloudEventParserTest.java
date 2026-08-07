package com.metaform.cxve.adapter.out.nats;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.cloudevents.core.builder.CloudEventBuilder;
import io.cloudevents.jackson.JsonFormat;
import java.net.URI;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class IssuanceCloudEventParserTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final IssuanceCloudEventParser parser = new IssuanceCloudEventParser();

    /** Mirrors how the EDC events-nats bridge serializes: type = payload FQCN, data = payload JSON. */
    private byte[] cloudEvent(String type, IssuanceEventData data) throws Exception {
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
    void parsesDeliveredEventAndExtractsHolderId() throws Exception {
        var bytes = cloudEvent(
                "org.eclipse.edc.issuerservice.issuance.events.CredentialDelivered",
                new IssuanceEventData("did:web:acme", "ctx-1", "holder-proc-1"));

        var event = parser.parseEnvelope(bytes);

        assertThat(parser.isCredentialDelivered(event)).isTrue();
        assertThat(parser.readData(event)).hasValueSatisfying(data ->
                assertThat(data.holderId()).isEqualTo("did:web:acme"));
    }

    @Test
    void doesNotMatchOtherIssuanceEvents() throws Exception {
        var bytes = cloudEvent(
                "org.eclipse.edc.issuerservice.issuance.events.IssuanceApproved",
                new IssuanceEventData("did:web:acme", "ctx-1", "holder-proc-1"));

        var event = parser.parseEnvelope(bytes);

        assertThat(parser.isCredentialDelivered(event)).isFalse();
    }
}
