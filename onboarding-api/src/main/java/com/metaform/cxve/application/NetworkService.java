package com.metaform.cxve.application;

import com.metaform.cxve.domain.DuplicateRegistrationException;
import com.metaform.cxve.domain.model.OspTenantRegistrationData;
import com.metaform.cxve.domain.model.PartnerRegistrationData;

public interface NetworkService {

    /**
     * Registers a partner company on behalf of the given authenticated client, which becomes the
     * routing target for the registration's status callbacks.
     *
     * @return the id of the created onboarding process
     */
    String registerPartner(String clientId, PartnerRegistrationData registrationData);

    /**
     * Registers a tenant invited by the given OSP client (CX-0009 §2.2.2) — the fully
     * OSP-mediated flow, running the same onboarding as {@link #registerPartner}.
     *
     * @return the id of the created onboarding process
     * @throws DuplicateRegistrationException when this client already submitted a registration
     *         under the payload's externalId (answered with 409 at the web boundary)
     */
    String registerTenant(String clientId, OspTenantRegistrationData tenantData);
}
