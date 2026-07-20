package com.beardyinc.cxve.infrastructure.nats;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.cloudevents.CloudEvent;
import io.cloudevents.jackson.JsonFormat;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Parses the structured-mode CloudEvents JSON produced by the EDC {@code events-nats} bridge.
 *
 * <p>The bridge sets the CloudEvent {@code type} to the payload's fully-qualified Java class name
 * (e.g. {@code org.eclipse.edc.issuerservice.issuance.events.CredentialDelivered}) and puts the
 * event payload — not the envelope — in {@code data}. This parser exposes the type and the decoded
 * {@link IssuanceEventData}, and is deliberately transport-free so it can be unit-tested with raw
 * bytes.
 */
@Component
public class IssuanceCloudEventParser {

    /** FQCN suffix of the event signalling credentials were delivered to the holder. */
    static final String CREDENTIAL_DELIVERED_TYPE_SUFFIX = ".CredentialDelivered";

    private final JsonFormat cloudEventFormat = new JsonFormat();
    // The event 'data' is a self-contained JSON object; a plain mapper suffices and keeps this
    // component independent of the web layer's JSON configuration.
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** Deserializes the CloudEvents envelope. */
    public CloudEvent parseEnvelope(byte[] message) {
        return cloudEventFormat.deserialize(message);
    }

    /** True if the event signals delivery of credentials to the holder (onboarding completion). */
    public boolean isCredentialDelivered(CloudEvent event) {
        return event.getType() != null && event.getType().endsWith(CREDENTIAL_DELIVERED_TYPE_SUFFIX);
    }

    /** Decodes the CloudEvent {@code data} into the issuance fields, if present. */
    public Optional<IssuanceEventData> readData(CloudEvent event) {
        var data = event.getData();
        if (data == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(objectMapper.readValue(data.toBytes(), IssuanceEventData.class));
        } catch (Exception e) {
            throw new IllegalArgumentException("Unable to decode issuance event data for id " + event.getId(), e);
        }
    }
}
