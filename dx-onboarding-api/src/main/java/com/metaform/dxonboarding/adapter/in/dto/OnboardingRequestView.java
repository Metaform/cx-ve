package com.metaform.dxonboarding.adapter.in.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.metaform.dxonboarding.domain.model.onboarding.Address;
import com.metaform.dxonboarding.domain.model.onboarding.CompanyType;
import com.metaform.dxonboarding.domain.model.onboarding.Declarations;
import com.metaform.dxonboarding.domain.model.onboarding.DocumentType;
import com.metaform.dxonboarding.domain.model.onboarding.LegalPerson;
import com.metaform.dxonboarding.domain.model.onboarding.OnboardingDocument;
import com.metaform.dxonboarding.domain.model.onboarding.OnboardingRequest;
import com.metaform.dxonboarding.domain.model.onboarding.OnboardingStatus;
import com.metaform.dxonboarding.domain.model.onboarding.RegistrationNumber;
import com.metaform.dxonboarding.domain.model.onboarding.ReviewDecision;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * An onboarding request with its status ({@code DataspaceOnboardingRequestDto}). {@code submittedAt}
 * is absent while it is a draft, {@code decision} until a decision is taken. The GTC and use case
 * agreements carry no {@code versionId} nor {@code accepted}: the applicant names a version, and
 * signs it with the document.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record OnboardingRequestView(
        String id,
        String version,
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
        DocumentRefView registrationExtractDocument,
        DocumentRefView powerOfAttorneyDocument,
        List<DocumentView> otherDocuments,
        ReviewDecision decision) {

    public static OnboardingRequestView from(OnboardingRequest request) {
        var data = request.data();
        var entity = data.legalEntity();
        var submittedAt = request.submittedAt();
        var gtcDocument = request.document(DocumentType.GTC, null);
        return new OnboardingRequestView(
                request.id(),
                String.valueOf(request.version()),
                request.businessId(),
                request.status(),
                request.connectorId(),
                submittedAt,
                entity == null && request.legalEntityId() == null ? null : LegalEntityView.from(request),
                data.legalPerson(),
                data.businessSites().stream()
                        .map(site -> new BusinessSiteView(site.siteId(), site.siteName(), site.siteNameAlias(),
                                site.mainAddress(), site.secondaryAddress(), site.registrationNumbers(), null,
                                request.createdAt(), request.updatedAt()))
                        .toList(),
                data.gtc() == null && gtcDocument.isEmpty() ? null : new ConsentView(data.gtcVersionNumber(),
                        submittedAt, gtcDocument.map(DocumentRefView::from).orElse(null)),
                data.ucas().stream()
                        .map(uca -> new UcaAcceptanceView(uca.useCaseId().id(), uca.useCaseId().displayName(),
                                uca.versionNumber(), submittedAt, request.document(DocumentType.UCA, uca.useCaseId())
                                .map(DocumentRefView::from).orElse(null)))
                        .toList(),
                data.declarations(),
                data.applicantReference(),
                request.document(DocumentType.REGISTRATION_EXTRACT, null).map(DocumentRefView::from).orElse(null),
                request.document(DocumentType.POWER_OF_ATTORNEY, null).map(DocumentRefView::from).orElse(null),
                request.documents(DocumentType.OTHER).stream().map(DocumentView::from).toList(),
                request.decision());
    }

    /**
     * The applying company, with the DECADE-X-ID the TSP assigned it ({@code legalEntityId}, absent
     * until approval).
     */
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

        static LegalEntityView from(OnboardingRequest request) {
            var entity = request.data().legalEntity();
            if (entity == null) {
                return new LegalEntityView(request.legalEntityId(), null, null, null, null, null, null, null, null,
                        null);
            }
            return new LegalEntityView(request.legalEntityId(), entity.preferredDid(), entity.legalName(),
                    entity.registrationCountry(), entity.companyType(), entity.companyTypeOther(),
                    entity.accreditationNumber(), entity.website(), entity.registrationNumbers(),
                    entity.legalAddress());
        }
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

    /** The GTC version signed, with its signed copy; {@code acceptedAt} is when the request was submitted. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ConsentView(String versionNumber, Instant acceptedAt, DocumentRefView document) {
    }

    /** A use case agreed to, named by the TSP, with its signed copy. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record UcaAcceptanceView(String useCaseId, String useCaseName, String versionNumber, Instant acceptedAt,
                                    DocumentRefView document) {
    }

    /** A document, as the request references it ({@code DocumentRefDto}). */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record DocumentRefView(UUID documentId, String filename, String contentType, long sizeBytes,
                                  Instant uploadedAt) {

        static DocumentRefView from(OnboardingDocument document) {
            return new DocumentRefView(document.documentId(), document.filename(), document.contentType(),
                    document.sizeBytes(), document.uploadedAt());
        }
    }
}
