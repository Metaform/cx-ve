package com.metaform.cxve.application;

import com.metaform.cxve.domain.model.PartnerRegistrationData;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import static java.util.Optional.ofNullable;

/**
 * Resolves the DID a participant is (or will be) provisioned under.
 *
 * <p>Deliberately shared rather than inlined at each use: the DID is needed both when the
 * participant is actually provisioned and when the start of an onboarding is announced, and those
 * two must agree. If they drifted, a subscriber would correlate on a DID the participant never gets.
 *
 * <p>The value is knowable before provisioning because it is either supplied by the caller or
 * derived from the configured template and the participant's short name.
 */
@Component
public class ParticipantDidResolver {

    private final String didTemplate;

    public ParticipantDidResolver(@Value("${participant.did.template:did:web:identity.cxve.localhost:}") String didTemplate) {
        this.didTemplate = didTemplate;
    }

    public String resolve(PartnerRegistrationData registrationData) {
        return ofNullable(registrationData.did()).orElseGet(() -> didTemplate + registrationData.shortName());
    }
}
