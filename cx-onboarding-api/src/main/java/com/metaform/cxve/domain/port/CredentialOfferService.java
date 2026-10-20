package com.metaform.cxve.domain.port;

import com.metaform.cxve.domain.model.OnboardingProcess;

/**
 * Offers the dataspace's membership credentials to a registered holder. The step right after
 * {@link HolderRegistrationService}: the holder entry says WHO may receive credentials, the offer
 * is what actually moves them towards the participant's wallet.
 *
 * <p>The offer is a push: the IssuerService resolves the holder's DID document and sends a DCP
 * {@code CredentialOfferMessage} to the {@code CredentialService} it advertises, and the
 * participant's own IdentityHub requests the offered credentials from there. So the participant's
 * DID document must resolve, and its credential service be reachable, at the moment this runs —
 * which is why a participant hosted by this environment is provisioned BEFORE it is registered.
 */
public interface CredentialOfferService {

    /** Sends the offer. Throws when the IssuerService rejects it or the holder cannot be reached. */
    void offerCredentials(OnboardingProcess process);
}
