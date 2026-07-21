package com.beardyinc.cxve.application;

import com.beardyinc.cxve.domain.model.PartnerRegistrationData;

public interface NetworkService {

    /**
     * Registers a partner company.
     */
    void registerPartner(PartnerRegistrationData registrationData);
}
