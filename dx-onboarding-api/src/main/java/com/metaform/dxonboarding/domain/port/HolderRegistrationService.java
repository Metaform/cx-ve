package com.metaform.dxonboarding.domain.port;

import com.metaform.dxonboarding.domain.model.onboarding.OnboardingRequest;

/**
 * Registers an approved participant as a credential holder with the IssuerService — the entry its
 * credentials are later generated from: the participant's DID, and the claims they carry (its
 * DECADE-X-ID).
 */
public interface HolderRegistrationService {

    /**
     * Registers the request's participant under its connector identity (its DID), with the
     * request's DECADE-X-ID. Idempotent: a holder that already exists — e.g. registered by another
     * dataspace's onboarding — gets the DECADE-X claims added, and keeps everything else.
     *
     * @throws RuntimeException when the IssuerService refuses or cannot be reached
     */
    void registerHolder(OnboardingRequest request);
}
