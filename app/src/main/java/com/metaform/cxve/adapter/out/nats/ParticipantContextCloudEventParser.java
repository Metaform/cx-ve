package com.metaform.cxve.adapter.out.nats;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.cloudevents.CloudEvent;
import io.cloudevents.jackson.JsonFormat;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Parses the structured-mode CloudEvents JSON produced by the EDC {@code events-nats} bridge for
 * IdentityHub participant-context events. Same format as {@link IssuanceCloudEventParser}: the
 * CloudEvent {@code type} is the payload's fully-qualified Java class name (e.g.
 * {@code org.eclipse.edc.identityhub.spi.participantcontext.events.ParticipantContextCreated}) and
 * {@code data} holds the event payload, not the envelope.
 */
@Component
public class ParticipantContextCloudEventParser {

    /** FQCN suffix of the event signalling an IdentityHub participant context was created. */
    static final String PARTICIPANT_CONTEXT_CREATED_TYPE_SUFFIX = ".ParticipantContextCreated";

    private final JsonFormat cloudEventFormat = new JsonFormat();
    // The event 'data' is a self-contained JSON object; a plain mapper suffices and keeps this
    // component independent of the web layer's JSON configuration.
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** Deserializes the CloudEvents envelope. */
    public CloudEvent parseEnvelope(byte[] message) {
        return cloudEventFormat.deserialize(message);
    }

    /** True if the event signals creation of a participant context (wallet provisioning progress). */
    public boolean isParticipantContextCreated(CloudEvent event) {
        return event.getType() != null && event.getType().endsWith(PARTICIPANT_CONTEXT_CREATED_TYPE_SUFFIX);
    }

    /** Decodes the CloudEvent {@code data} into the participant-context fields, if present. */
    public Optional<ParticipantContextEventData> readData(CloudEvent event) {
        var data = event.getData();
        if (data == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(objectMapper.readValue(data.toBytes(), ParticipantContextEventData.class));
        } catch (Exception e) {
            throw new IllegalArgumentException("Unable to decode participant context event data for id " + event.getId(), e);
        }
    }
}
