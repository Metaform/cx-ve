package com.beardyinc.cxve.service;

import com.beardyinc.cxve.model.PartnerRegistrationData;

public interface NetworkService {

    /**
     * Registers a partner company.
     */
    void registerPartner(PartnerRegistrationData registrationData);
}
