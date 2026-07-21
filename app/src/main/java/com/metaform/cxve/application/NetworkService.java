package com.metaform.cxve.application;

import com.metaform.cxve.domain.model.PartnerRegistrationData;

public interface NetworkService {

    /**
     * Registers a partner company.
     */
    void registerPartner(PartnerRegistrationData registrationData);
}
