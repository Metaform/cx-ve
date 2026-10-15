package com.metaform.cxve.domain.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import java.util.List;

/**
 * DTO for incoming partner registration requests (the payload of
 * POST /api/administration/registration/network/partnerregistration, CX-0009 §2.2.1). The
 * mandatory components mirror the spec's field table — externalId, name, city, streetName,
 * countryAlpha2Code, region, companyRoles, uniqueIds and userDetails — and are enforced at the
 * web boundary via {@code @Valid} on the controller, so a request missing any of them is
 * rejected with 400 before it reaches the onboarding flow. ({@code userDetails} is held to
 * {@code @NotEmpty} rather than mere presence: an empty initial-user list is unusable.) All
 * other components are optional — notably the BPN: when absent, the BusinessPartnerNumberService
 * assigns one at the BPN step of the onboarding.
 *
 * <p>{@code consents} is INTERNAL: the §2.2.2 tenant flow maps its collected consents into it
 * (see {@link OspTenantRegistrationData#toRegistrationData()}) so they survive persistence; the
 * legacy §2.2.1 endpoint neither documents nor validates it. A legacy caller sending it anyway
 * gets it persisted, which is harmless — nothing on that path consumes it.
 *
 * <p>Tolerant reader on purpose ({@code @JsonIgnoreProperties}): the normative OpenAPI declares
 * {@code additionalProperties: true}, and the JPA store re-reads persisted payload JSON with a
 * plain ObjectMapper — rows written under earlier revisions (which carried {@code agreements}
 * and {@code fileIds}) must keep deserializing.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PartnerRegistrationData(
        // Path-safe like the tenant flow's: legacy registrations are readable through the same
        // recovery endpoints, so their ids must be addressable there too.
        @NotBlank @Pattern(regexp = OspTenantRegistrationData.EXTERNAL_ID_PATTERN,
                message = OspTenantRegistrationData.EXTERNAL_ID_MESSAGE) String externalId,
        @NotBlank String name,
        @NotBlank String city,
        @NotBlank String streetName,
        @NotBlank String countryAlpha2Code,
        @NotBlank String region,
        @NotEmpty List<CompanyRoleId> companyRoles,
        @NotEmpty List<@Valid CompanyUniqueIdData> uniqueIds,
        @NotEmpty List<@Valid UserDetailData> userDetails,
        String bpn,
        String shortName,
        String streetNumber,
        String streetAdditional,
        String zipCode,
        String did,
        Boolean autoSubmit,
        List<ConsentData> consents
) {

    public PartnerRegistrationData withBpn(String newBpn) {
        return new PartnerRegistrationData(externalId, name, city, streetName, countryAlpha2Code, region,
                companyRoles, uniqueIds, userDetails, newBpn, shortName, streetNumber, streetAdditional,
                zipCode, did, autoSubmit, consents);
    }

    public PartnerRegistrationData withDid(String newDid) {
        return new PartnerRegistrationData(externalId, name, city, streetName, countryAlpha2Code, region,
                companyRoles, uniqueIds, userDetails, bpn, shortName, streetNumber, streetAdditional,
                zipCode, newDid, autoSubmit, consents);
    }
}
