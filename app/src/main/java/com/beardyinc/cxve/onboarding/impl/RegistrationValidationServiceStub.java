package com.beardyinc.cxve.onboarding.impl;

import com.beardyinc.cxve.model.PartnerRegistrationData;
import com.beardyinc.cxve.onboarding.RegistrationValidationService;
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

    @Override
    public ValidationResult validate(PartnerRegistrationData registrationData) {
        var violations = new ArrayList<String>();
        if (registrationData.name() == null || registrationData.name().isBlank()) {
            violations.add("company name is required");
        }
        if (registrationData.companyRoles() == null || registrationData.companyRoles().isEmpty()) {
            violations.add("at least one company role is required");
        }
        return violations.isEmpty() ? ValidationResult.ok() : ValidationResult.rejected(List.copyOf(violations));
    }
}
