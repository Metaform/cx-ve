package com.beardyinc.cxve.onboarding.impl;

import com.beardyinc.cxve.model.PartnerRegistrationData;
import com.beardyinc.cxve.onboarding.BusinessPartnerNumberService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Locale;

/**
 * Placeholder BPNL handling. Reuses a BPN supplied on the registration, otherwise derives a
 * deterministic placeholder. A real implementation would call the BPN issuer per CX-0010.
 */
@Service
public class BusinessPartnerNumberServiceStub implements BusinessPartnerNumberService {

    private static final Logger log = LoggerFactory.getLogger(BusinessPartnerNumberServiceStub.class);

    @Override
    public String resolveOrCreate(PartnerRegistrationData registrationData) {
        if (registrationData.bpn() != null && !registrationData.bpn().isBlank()) {
            log.debug("Reusing supplied BPN {} for externalId={}", registrationData.bpn(), registrationData.externalId());
            return registrationData.bpn();
        }
        var seed = registrationData.externalId() == null ? "UNKNOWN" : registrationData.externalId();
        var suffix = String.format("%08X", Math.abs(seed.hashCode()));
        var bpn = ("BPNL" + suffix + "000000").substring(0, 16).toUpperCase(Locale.ROOT);
        log.debug("Generated placeholder BPN {} for externalId={}", bpn, registrationData.externalId());
        return bpn;
    }
}
