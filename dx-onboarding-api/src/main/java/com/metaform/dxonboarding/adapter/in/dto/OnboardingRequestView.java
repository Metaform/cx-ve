package com.metaform.dxonboarding.adapter.in.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.metaform.dxonboarding.domain.model.onboarding.Address;
import com.metaform.dxonboarding.domain.model.onboarding.CompanyType;
import com.metaform.dxonboarding.domain.model.onboarding.Declarations;
import com.metaform.dxonboarding.domain.model.onboarding.DocumentRef;
import com.metaform.dxonboarding.domain.model.onboarding.LegalPerson;
import com.metaform.dxonboarding.domain.model.onboarding.OnboardingRequest;
import com.metaform.dxonboarding.domain.model.onboarding.OnboardingStatus;
import com.metaform.dxonboarding.domain.model.onboarding.RegistrationNumber;
import com.metaform.dxonboarding.domain.model.onboarding.ReviewDecision;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * An onboarding request as submitted, with its status ({@code DataspaceOnboardingRequestDto}).
 * {@code decision} is absent until a decision is taken.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record OnboardingRequestView(
        String id,
        String businessId,
        OnboardingStatus status,
        String connectorId,
        Instant submittedAt,
        LegalEntityView legalEntity,
        LegalPerson legalPerson,
        List<BusinessSiteView> businessSites,
        ConsentView gtc,
        List<UcaAcceptanceView> ucas,
        Declarations declarations,
        String applicantReference,
        DocumentRef registrationExtractDocument,
        DocumentRef powerOfAttorneyDocument,
        ReviewDecision decision) {

    public static OnboardingRequestView from(OnboardingRequest request) {
        var data = request.data();
        var entity = data.legalEntity();
        var gtc = data.gtc();
        var submittedAt = request.submittedAt();
        return new OnboardingRequestView(
                request.id(),
                request.businessId(),
                request.status(),
                request.connectorId(),
                submittedAt,
                new LegalEntityView(request.legalEntityId(), entity.preferredDid(), entity.legalName(),
                        entity.registrationCountry(), entity.companyType(), entity.companyTypeOther(),
                        entity.accreditationNumber(), entity.website(), entity.registrationNumbers(),
                        entity.legalAddress()),
                data.legalPerson(),
                data.businessSites().stream()
                        .map(site -> new BusinessSiteView(site.siteId(), site.siteName(), site.siteNameAlias(),
                                site.mainAddress(), site.secondaryAddress(), site.registrationNumbers(), null,
                                submittedAt, submittedAt))
                        .toList(),
                new ConsentView(gtc.versionId(), gtc.versionNumber(), gtc.accepted(), submittedAt, request.gtcDocument()),
                data.ucas().stream()
                        .map(uca -> new UcaAcceptanceView(uca.useCaseId(), uca.useCaseName(), uca.versionId(),
                                uca.versionNumber(), uca.accepted(), submittedAt,
                                request.ucaDocuments().get(uca.useCaseId())))
                        .toList(),
                data.declarations(),
                data.applicantReference(),
                request.registrationExtractDocument(),
                request.powerOfAttorneyDocument(),
                request.decision());
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record LegalEntityView(
            String legalEntityId,
            String preferredDid,
            String legalName,
            String registrationCountry,
            CompanyType companyType,
            String companyTypeOther,
            String accreditationNumber,
            String website,
            List<RegistrationNumber> registrationNumbers,
            Address legalAddress) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record BusinessSiteView(
            String siteId,
            String siteName,
            String siteNameAlias,
            Address mainAddress,
            Address secondaryAddress,
            List<RegistrationNumber> registrationNumbers,
            String ownedBy,
            Instant creationDate,
            Instant updatingDate) {
    }

    /** An accepted versioned document — the GTC — with the signed copy that was submitted. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ConsentView(UUID versionId, String versionNumber, Boolean accepted, Instant acceptedAt,
                              DocumentRef document) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record UcaAcceptanceView(String useCaseId, String useCaseName, UUID versionId, String versionNumber,
                                    Boolean accepted, Instant acceptedAt, DocumentRef document) {
    }
}
