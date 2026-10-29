package com.metaform.dxonboarding.domain.port;

import com.metaform.dxonboarding.domain.model.onboarding.OnboardingRequest;

/**
 * Has the IssuerService offer a registered holder the DECADE-X credentials: a DCP CredentialOffer
 * pushed to the CredentialService its DID document advertises — so the participant's wallet must
 * exist and its DID must resolve by then. The participant then requests the credentials with its
 * own wallet.
 */
public interface CredentialOfferService {

    /**
     * Sends the offer. NOT idempotent: a second offer has the wallet hold the credentials twice.
     *
     * @throws RuntimeException when the IssuerService refuses, or cannot reach the holder
     */
    void offerCredentials(OnboardingRequest request);
}
