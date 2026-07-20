package com.beardyinc.cxve.onboarding.impl;

import com.beardyinc.cxve.model.PartnerRegistrationData;
import com.beardyinc.cxve.onboarding.RegistrationValidationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Placeholder validation. A real implementation would check the participant is not already onboarded,
 * that no other registration is in flight, and that the certificates of conformity are valid for the
 * requested roles. For now it only enforces the minimal shape needed to proceed.
 */
@Service
public class RegistrationValidationServiceStub implements RegistrationValidationService {

    private static final Logger log = LoggerFactory.getLogger(RegistrationValidationServiceStub.class);

    @Override
    public ValidationResult validate(PartnerRegistrationData registrationData) {
        var violations = new ArrayList<String>();
        if (registrationData.name() == null || registrationData.name().isBlank()) {
            violations.add("company name is required");
        }
        if (registrationData.companyRoles() == null || registrationData.companyRoles().isEmpty()) {
            violations.add("at least one company role is required");
        }
        if (violations.isEmpty()) {
            log.debug("Validation passed for externalId={}", registrationData.externalId());
            return ValidationResult.ok();
        }
        log.debug("Validation failed for externalId={}: {}", registrationData.externalId(), violations);
        return ValidationResult.rejected(List.copyOf(violations));
    }
}
