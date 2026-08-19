package com.metaform.cxve.application;

import com.metaform.cxve.domain.model.PartnerRegistrationData;

public interface NetworkService {

    /**
     * Registers a partner company on behalf of the given authenticated client, which becomes the
     * routing target for the registration's status callbacks.
     *
     * @return the id of the created onboarding process
     */
    String registerPartner(String clientId, PartnerRegistrationData registrationData);
}
