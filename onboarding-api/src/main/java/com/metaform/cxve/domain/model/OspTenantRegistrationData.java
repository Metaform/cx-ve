package com.metaform.cxve.domain.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * DTO for tenant registrations submitted by an OSP (the payload of
 * POST /api/administration/osp/v2/tenant-registration, CX-0009 §2.2.2) — the fully OSP-mediated
 * flow: the OSP has collected everything, including the participant's consents, and the company
 * never interacts with this operator directly. Unlike the §2.2.1 payload there are no
 * {@code companyRoles} (the spec implies Data Provider/Consumer only), no {@code bpn}, and no
 * {@code autoSubmit} (submission is implicit in the POST).
 *
 * <p>{@code consents} is Mandatory and MUST contain all three required kinds — enforced by
 * {@link #isCoveringRequiredConsentKinds()}, mirroring the normative schema's
 * {@code minItems: 3} + per-kind {@code contains} clauses.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record OspTenantRegistrationData(
        @NotBlank String externalId,
        @NotBlank String name,
        @NotBlank String city,
        @NotBlank String streetName,
        @NotBlank String countryAlpha2Code,
        @NotEmpty List<@Valid CompanyUniqueIdData> uniqueIds,
        @NotEmpty List<@Valid UserDetailData> userDetails,
        @NotEmpty List<@Valid ConsentData> consents,
        String region,
        String shortName,
        String streetNumber,
        String streetAdditional,
        String zipCode,
        String did
) {

    private static final Set<ConsentKind> REQUIRED_KINDS = EnumSet.of(
            ConsentKind.CX_OPERATING_MODEL, ConsentKind.CX_TEN_GOLDEN_RULES, ConsentKind.CX_DATA_EXCHANGE_GOVERNANCE);

    @JsonIgnore
    @AssertTrue(message = "consents must include CX_OPERATING_MODEL, CX_TEN_GOLDEN_RULES and CX_DATA_EXCHANGE_GOVERNANCE")
    public boolean isCoveringRequiredConsentKinds() {
        return consents != null && consents.stream()
                .map(ConsentData::kind)
                .collect(Collectors.toSet())
                .containsAll(REQUIRED_KINDS);
    }

    /**
     * The internal registration payload the shared onboarding flow runs on: role implied
     * {@code ACTIVE_PARTICIPANT}, no BPN (assigned at the BPN step), {@code autoSubmit} true —
     * the tenant flow is fully automatic, its state machine enters at SUBMITTED.
     */
    public PartnerRegistrationData toRegistrationData() {
        return new PartnerRegistrationData(externalId, name, city, streetName, countryAlpha2Code, region,
                List.of(CompanyRoleId.ACTIVE_PARTICIPANT), uniqueIds, userDetails,
                null, shortName, streetNumber, streetAdditional, zipCode, did, Boolean.TRUE, consents);
    }
}
