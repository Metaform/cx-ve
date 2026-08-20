package com.metaform.cxve.hub.domain.port;

import com.metaform.cxve.hub.domain.model.MemberData;

/**
 * The hub's view of the Onboarding API, called in the onboarding-service-provider role (OAuth2
 * client-credentials against the VE's OSP IdP).
 */
public interface OnboardingApi {

    /**
     * Registers (or overwrites) this app's status-callback address with the Onboarding API. The
     * registration is keyed on the hub's client identity, so re-registering is idempotent.
     */
    void registerCallback();

    /**
     * Submits the partner registration under the given external id and DID.
     *
     * @return the id of the onboarding process the API created.
     */
    String submitRegistration(String externalId, String did, MemberData data);
}
