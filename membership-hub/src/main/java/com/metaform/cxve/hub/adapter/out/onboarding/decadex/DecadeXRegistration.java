package com.metaform.cxve.hub.adapter.out.onboarding.decadex;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * The Decade-X shape of a member request's {@code registration} object: the TSP's onboarding
 * request ({@code OnboardingRequestWriteDto}), passed through as is — minus what the hub fills in
 * itself: {@code legalEntity.legalName} (the member's {@code name}), {@code legalEntity.preferredDid}
 * (its DID) and {@code applicantReference} (the hub's external id). Sending one of those is refused
 * like any other unknown property.
 *
 * <p>The constraints mirror what the TSP refuses a request for, so a bad request fails in the hub
 * — for a member hosted here, BEFORE its deployment rather than at the submission after it. The
 * documents the TSP also requires (the signed GTC, one signed UCA per use case) are not part of
 * it: the hub generates placeholders ({@link PlaceholderDocuments}).
 */
public record DecadeXRegistration(
        @NotNull @Valid LegalEntity legalEntity,
        @NotNull @Valid LegalPerson legalPerson,
        List<@NotNull @Valid BusinessSite> businessSites,
        @NotNull @Valid Gtc gtc,
        List<@NotNull @Valid Uca> ucas,
        @NotNull @Valid Declarations declarations
) {

    public DecadeXRegistration {
        businessSites = businessSites == null ? List.of() : businessSites;
        ucas = ucas == null ? List.of() : ucas;
    }

    @AssertTrue(message = "must not list a useCaseId more than once")
    public boolean isUcasDistinct() {
        var seen = new HashSet<String>();
        return ucas.stream().filter(Objects::nonNull).map(Uca::useCaseId).filter(Objects::nonNull).allMatch(seen::add);
    }

    public record LegalEntity(
            @NotBlank String registrationCountry,
            @NotNull CompanyType companyType,
            String companyTypeOther,
            String accreditationNumber,
            String website,
            @NotEmpty List<@NotNull @Valid RegistrationNumber> registrationNumbers,
            @NotNull @Valid LegalAddress legalAddress) {

        @AssertTrue(message = "is required when companyType is OTHER")
        public boolean isCompanyTypeOtherGiven() {
            return companyType != CompanyType.OTHER || (companyTypeOther != null && !companyTypeOther.isBlank());
        }
    }

    public enum CompanyType {
        PRIVATE_LIMITED_COMPANY,
        LIMITED_LIABILITY_COMPANY,
        EUROPEAN_COMPANY,
        COOPERATIVE,
        PUBLIC_AUTHORITY,
        OTHER
    }

    /** Named as they appear on the wire. */
    public enum RegistrationScheme {
        taxID,
        vatID,
        leiCode,
        duns,
        eori,
        euid,
        bvd,
        cageCode
    }

    public record RegistrationNumber(@NotNull RegistrationScheme scheme, @NotBlank String value) {
    }

    /** The legal address, of which street, locality and country are required. */
    public record LegalAddress(@NotBlank String street, String postalCode, @NotBlank String locality,
                               @NotBlank String countryCode, String countryName) {
    }

    public record Address(String street, String postalCode, String locality, String countryCode, String countryName) {
    }

    /**
     * @param powerOfAttorneyValidUntil an ISO date; a string here, because the hub never submits a
     *                                  power-of-attorney document and only passes the value on
     */
    public record LegalPerson(
            @NotBlank String fullName,
            @NotBlank @Email String email,
            String role,
            String phoneNumber,
            Address headquartersAddress,
            @Pattern(regexp = "\\d{4}-\\d{2}-\\d{2}") String powerOfAttorneyValidUntil) {
    }

    public record BusinessSite(
            String siteId,
            String siteName,
            String siteNameAlias,
            Address mainAddress,
            Address secondaryAddress,
            List<@NotNull @Valid RegistrationNumber> registrationNumbers) {
    }

    /** The accepted General Terms and Conditions. */
    public record Gtc(@NotNull UUID versionId, @NotBlank String versionNumber,
                      @NotNull @AssertTrue(message = "the GTC must be accepted") Boolean accepted) {
    }

    /** An accepted use case agreement; the TSP's reviewer-only fields are not part of it. */
    public record Uca(@NotBlank String useCaseId, String useCaseName, UUID versionId, String versionNumber,
                      @NotNull @AssertTrue(message = "the use case agreement must be accepted") Boolean accepted) {
    }

    public record Declarations(
            @NotNull @AssertTrue(message = "must be affirmed") Boolean informationAccurate,
            @NotNull @AssertTrue(message = "must be affirmed") Boolean authorisedToAct,
            @NotNull @AssertTrue(message = "must be affirmed") Boolean evidenceMayBeRequested) {
    }
}
