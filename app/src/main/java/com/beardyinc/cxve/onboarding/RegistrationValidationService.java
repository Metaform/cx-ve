package com.beardyinc.cxve.onboarding;

import com.beardyinc.cxve.model.PartnerRegistrationData;

import java.util.List;

/**
 * CX-0006 mandatory validation: the applicant is not already onboarded, no other registration is
 * in flight for them, and the supplied certificates of conformity are valid for the requested roles.
 */
public interface RegistrationValidationService {

    ValidationResult validate(PartnerRegistrationData registrationData);

    /**
     * Outcome of validation. {@code valid == true} implies {@code violations} is empty.
     */
    record ValidationResult(boolean valid, List<String> violations) {

        public static ValidationResult ok() {
            return new ValidationResult(true, List.of());
        }

        public static ValidationResult rejected(List<String> violations) {
            return new ValidationResult(false, violations);
        }
    }
}
