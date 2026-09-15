package com.metaform.cxve.hub.domain.port;

import com.metaform.cxve.hub.domain.model.Membership;

/**
 * Triggers issuer-initiated credential issuance for a member: the IssuerService sends a DCP
 * CredentialOffer to the member's Credential Service, resolved from its DID document.
 *
 * <p>This is what replaces the Tenant Manager deployment for an externally hosted member. An
 * internally provisioned one needs no offer: the wallet the CFM orchestration creates requests
 * its credentials itself as the last step of that orchestration. An external member has no such
 * step — nothing in this environment would ever ask its wallet to request anything — so the
 * issuer has to make the first move.
 */
public interface CredentialOfferService {

    /**
     * Offers the membership credentials to the member's own Credential Service. The holder entry
     * must already exist with the IssuerService (the Onboarding API creates it before confirming
     * the registration, which is what makes the CONFIRMED callback the right trigger), and the
     * member's DID must resolve FROM THIS ENVIRONMENT. Throws on failure.
     */
    void sendOffer(Membership membership);
}
