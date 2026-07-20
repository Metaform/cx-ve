package com.beardyinc.cxve.onboarding;

import com.beardyinc.cxve.model.PartnerRegistrationData;

/**
 * Resolves the applicant's Business Partner Number (BPNL) per CX-0010, creating one if the company
 * is not yet known. If the registration already carries a BPN, that value is validated and reused.
 */
public interface BusinessPartnerNumberService {

    /**
     * @return the BPNL to associate with the onboarding (existing or newly minted).
     */
    String resolveOrCreate(PartnerRegistrationData registrationData);
}
