package com.metaform.cxve.application;

import com.metaform.cxve.domain.model.PartnerRegistrationData;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Resolves the DID a participant is (or will be) provisioned under.
 *
 * <p>Deliberately shared rather than inlined at each use: the DID is needed both when the
 * participant is actually provisioned and when the start of an onboarding is announced, and those
 * two must agree. If they drifted, a subscriber would correlate on a DID the participant never gets.
 *
 * <p>The value is knowable before provisioning because it is either supplied by the caller or
 * derived from the configured template: caller-supplied did → template + shortName → template +
 * externalId (shortName is Optional per spec; externalId is always present, though only unique
 * per OSP — externalIds are UUIDs by convention, and a collision is caught by the duplicate-DID
 * check).
 */
@Component
public class ParticipantDidResolver {

    private final String didTemplate;

    public ParticipantDidResolver(@Value("${participant.did.template:did:web:identity.cxve.localhost:}") String didTemplate) {
        this.didTemplate = didTemplate;
    }

    public String resolve(PartnerRegistrationData registrationData) {
        if (registrationData.did() != null && !registrationData.did().isBlank()) {
            return registrationData.did();
        }
        var suffix = registrationData.shortName() != null && !registrationData.shortName().isBlank()
                ? registrationData.shortName()
                : registrationData.externalId();
        return didTemplate + suffix;
    }
}
