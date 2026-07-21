package com.metaform.cxve.adapter.out.validation;

import com.metaform.cxve.domain.model.PartnerRegistration;
import com.metaform.cxve.domain.model.PartnerRegistrationData;
import com.metaform.cxve.domain.port.OnboardingRepository;
import com.metaform.cxve.domain.port.RegistrationValidationService;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Placeholder validation. Enforces the minimal shape needed to proceed and the CX-0006 duplicate
 * checks: a registration whose BPN, DID or any unique id matches an already onboarded partner or a
 * registration still in flight is rejected. A real implementation would additionally check that
 * the certificates of conformity are valid for the requested roles.
 */
@Service
public class RegistrationValidationServiceImpl implements RegistrationValidationService {

    private static final Logger log = LoggerFactory.getLogger(RegistrationValidationServiceImpl.class);

    private final OnboardingRepository onboardingRepository;

    public RegistrationValidationServiceImpl(OnboardingRepository onboardingRepository) {
        this.onboardingRepository = onboardingRepository;
    }

    @Override
    public ValidationResult validate(PartnerRegistrationData registrationData) {
        var violations = new ArrayList<String>();
        if (registrationData.name() == null || registrationData.name().isBlank()) {
            violations.add("company name is required");
        }
        if (registrationData.companyRoles() == null || registrationData.companyRoles().isEmpty()) {
            violations.add("at least one company role is required");
        }
        checkForDuplicate(registrationData, violations);
        if (violations.isEmpty()) {
            log.debug("Validation passed for externalId={}", registrationData.externalId());
            return ValidationResult.ok();
        }
        log.debug("Validation failed for externalId={}: {}", registrationData.externalId(), violations);
        return ValidationResult.rejected(List.copyOf(violations));
    }

    private void checkForDuplicate(PartnerRegistrationData registrationData, List<String> violations) {
        if (registrationData.bpn() != null && !registrationData.bpn().isBlank()) {
            onboardingRepository.findActiveByBpn(registrationData.bpn()).ifPresent(match ->
                    violations.add(duplicate(match, "BPN '%s'".formatted(registrationData.bpn()))));
        }
        if (registrationData.did() != null && !registrationData.did().isBlank()) {
            onboardingRepository.findActiveByDid(registrationData.did()).ifPresent(match ->
                    violations.add(duplicate(match, "DID '%s'".formatted(registrationData.did()))));
        }
        if (registrationData.uniqueIds() != null) {
            registrationData.uniqueIds().forEach(uniqueId ->
                    onboardingRepository.findActiveByUniqueId(uniqueId).ifPresent(match ->
                            violations.add(duplicate(match, "unique id %s '%s'"
                                    .formatted(uniqueId.type(), uniqueId.value())))));
        }
    }

    private static String duplicate(PartnerRegistration match, String property) {
        return match.inFlight()
                ? "a registration with %s is already in flight".formatted(property)
                : "a partner with %s is already registered".formatted(property);
    }
}
