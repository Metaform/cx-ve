package com.metaform.dxonboarding.domain.model.onboarding;

import java.util.List;
import java.util.Optional;

/**
 * An onboarding request's data, as the applicant writes it — on creation, and on every correction
 * of the draft, which replaces it whole. Anything may still be missing in a draft; what a request
 * needs to be submitted is {@link SubmitCheck}'s.
 */
public record OnboardingRequestData(
        LegalEntity legalEntity,
        LegalPerson legalPerson,
        List<BusinessSite> businessSites,
        ConsentDeclaration gtc,
        List<UseCaseAgreement> ucas,
        Declarations declarations,
        String applicantReference) {

    public OnboardingRequestData {
        businessSites = businessSites == null ? List.of() : List.copyOf(businessSites);
        ucas = ucas == null ? List.of() : List.copyOf(ucas);
    }

    /** As the TSP keeps it: the DID trimmed and lower-cased. */
    public OnboardingRequestData normalized() {
        return legalEntity == null ? this : withLegalEntity(legalEntity.normalized());
    }

    public OnboardingRequestData withoutLegalEntityId() {
        return legalEntity == null ? this : withLegalEntity(legalEntity.withoutLegalEntityId());
    }

    /** The participant's DID, if given. */
    public Optional<String> preferredDid() {
        return Optional.ofNullable(legalEntity).map(LegalEntity::preferredDid).filter(did -> !did.isBlank());
    }

    /** The DECADE-X-ID declared out-of-band (see {@link LegalEntity#legalEntityId()}), if any. */
    public Optional<String> declaredLegalEntityId() {
        return Optional.ofNullable(legalEntity).map(LegalEntity::legalEntityId);
    }

    /** The GTC version the applicant signs; null when none is named. */
    public String gtcVersionNumber() {
        return gtc == null ? null : gtc.versionNumber();
    }

    /** This request's agreement to the use case, if it has one. */
    public Optional<UseCaseAgreement> uca(UseCase useCase) {
        return ucas.stream().filter(uca -> uca.useCaseId() == useCase).findFirst();
    }

    private OnboardingRequestData withLegalEntity(LegalEntity entity) {
        return new OnboardingRequestData(entity, legalPerson, businessSites, gtc, ucas, declarations,
                applicantReference);
    }
}
