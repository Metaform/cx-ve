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
 * Performs logic validation of the onboarding request, such as duplicate checks or in-flight checks.
 * This does <em>not</em> perform any shape validation - this is done by annotations on the {@link PartnerRegistrationData}
 * class and left to the ingress validation of Spring.
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
        var violations = checkForDuplicate(registrationData);
        if (violations.isEmpty()) {
            log.debug("Validation passed for externalId={}", registrationData.externalId());
            return ValidationResult.ok();
        }
        log.debug("Validation failed for externalId={}: {}", registrationData.externalId(), violations);
        return ValidationResult.rejected(List.copyOf(violations));
    }

    private List<String> checkForDuplicate(PartnerRegistrationData registrationData) {
        var violations = new ArrayList<String>();
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
        return violations;
    }

    private static String duplicate(PartnerRegistration match, String property) {
        return match.inFlight()
                ? "a registration with %s is already in flight".formatted(property)
                : "a partner with %s is already registered".formatted(property);
    }
}
