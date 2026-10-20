package com.metaform.cxve.hub.adapter.out.onboarding.decadex;

import com.fasterxml.jackson.annotation.JsonValue;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;

/**
 * The DECADE-X shape of a member request's {@code registration} object: the TSP's onboarding
 * request ({@code OnboardingRequestWriteDto}), passed through as is — minus what the hub fills in
 * itself: {@code legalEntity.legalName} (the member's {@code name}), {@code legalEntity.preferredDid}
 * (its DID) and {@code applicantReference} (the hub's external id). Sending one of those is refused
 * like any other unknown property, and so are the fields of the TSP's previous API version
 * ({@code versionId} and {@code accepted} on {@code gtc} and {@code ucas}).
 *
 * <p>The constraints mirror what the TSP refuses a request for — its required fields, its
 * {@code maxLength} and {@code maxItems} bounds, its use cases — so a bad request fails in the
 * hub: for a member hosted here, BEFORE its deployment rather than at the submission after it.
 * The documents the TSP also requires (the signed GTC, one signed UCA per use case) are not part
 * of it: the hub generates placeholders ({@link PlaceholderDocuments}).
 */
public record DecadeXRegistration(
        @NotNull @Valid LegalEntity legalEntity,
        @NotNull @Valid LegalPerson legalPerson,
        @Size(max = 100) List<@NotNull @Valid BusinessSite> businessSites,
        @NotNull @Valid Gtc gtc,
        @Size(max = 50) List<@NotNull @Valid Uca> ucas,
        @NotNull @Valid Declarations declarations
) {

    public DecadeXRegistration {
        businessSites = businessSites == null ? List.of() : businessSites;
        ucas = ucas == null ? List.of() : ucas;
    }

    @AssertTrue(message = "must not list a useCaseId more than once")
    public boolean isUcasDistinct() {
        var seen = new HashSet<UseCase>();
        return ucas.stream().filter(Objects::nonNull).map(Uca::useCaseId).filter(Objects::nonNull).allMatch(seen::add);
    }

    public record LegalEntity(
            @NotBlank @Size(max = 2) String registrationCountry,
            @NotNull CompanyType companyType,
            @Size(max = 255) String companyTypeOther,
            @Size(max = 255) String accreditationNumber,
            @Size(max = 2048) String website,
            @NotEmpty @Size(max = 20) List<@NotNull @Valid RegistrationNumber> registrationNumbers,
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

    /** The TSP's use cases, by the id a use case agreement names one with. */
    public enum UseCase {
        EXPORT_CONTROL("export-control"),
        PRODUCT_CONFORMITY("product-conformity"),
        CRITICAL_SUPPLY_CHAIN("critical-supply-chain"),
        ENGINEERING("engineering"),
        DEVELOPMENT_AND_INDUSTRIALISATION("development-and-industrialisation"),
        DEMAND_AND_CAPACITY_MANAGEMENT("demand-and-capacity-management"),
        NON_QUALITY_MANAGEMENT("non-quality-management"),
        SUBSTANCE_TRACKING("substance-tracking");

        private final String id;

        UseCase(String id) {
            this.id = id;
        }

        /** The id on the wire, e.g. {@code product-conformity}. */
        @JsonValue
        public String id() {
            return id;
        }
    }

    public record RegistrationNumber(@NotNull RegistrationScheme scheme, @NotBlank @Size(max = 255) String value) {
    }

    /** The legal address, of which street, locality and country are required. */
    public record LegalAddress(@NotBlank @Size(max = 255) String street, @Size(max = 32) String postalCode,
                               @NotBlank @Size(max = 255) String locality, @NotBlank @Size(max = 2) String countryCode,
                               @Size(max = 255) String countryName) {
    }

    public record Address(@Size(max = 255) String street, @Size(max = 32) String postalCode,
                          @Size(max = 255) String locality, @Size(max = 2) String countryCode,
                          @Size(max = 255) String countryName) {
    }

    /**
     * @param powerOfAttorneyValidUntil an ISO date; a string here, because the hub never submits a
     *                                  power-of-attorney document and only passes the value on
     */
    public record LegalPerson(
            @NotBlank @Size(max = 255) String fullName,
            @NotBlank @Email @Size(max = 255) String email,
            @Size(max = 255) String role,
            @Size(max = 32) String phoneNumber,
            @Valid Address headquartersAddress,
            @Pattern(regexp = "\\d{4}-\\d{2}-\\d{2}") String powerOfAttorneyValidUntil) {
    }

    public record BusinessSite(
            @Size(max = 64) String siteId,
            @Size(max = 255) String siteName,
            @Size(max = 255) String siteNameAlias,
            @Valid Address mainAddress,
            @Valid Address secondaryAddress,
            @Size(max = 20) List<@NotNull @Valid RegistrationNumber> registrationNumbers) {
    }

    /** The version of the General Terms and Conditions the member accepts. */
    public record Gtc(@NotBlank @Size(max = 32) String versionNumber) {
    }

    /**
     * A use case agreement the member accepts. {@code useCaseName} is accepted for the sake of
     * existing payloads, but not passed on: the TSP names the use case itself.
     */
    public record Uca(@NotNull UseCase useCaseId, @Size(max = 255) String useCaseName,
                      @Size(max = 32) String versionNumber) {
    }

    public record Declarations(
            @NotNull @AssertTrue(message = "must be affirmed") Boolean informationAccurate,
            @NotNull @AssertTrue(message = "must be affirmed") Boolean authorisedToAct,
            @NotNull @AssertTrue(message = "must be affirmed") Boolean evidenceMayBeRequested) {
    }
}
