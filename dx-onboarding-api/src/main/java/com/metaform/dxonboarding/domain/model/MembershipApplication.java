package com.metaform.dxonboarding.domain.model;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * A Decade-X membership application, as an onboarding service provider submits it.
 *
 * @param applicationRef the submitter's own reference — echoed in the decision webhook, which is
 *                       how the submitter correlates it
 * @param legalName      the applicant's legal name
 * @param decadeXId      the applicant's Decade-X-ID — Decade-X's member id, {@code DX-} and 8 digits
 * @param did            the applicant's DID, under which it is admitted
 * @param country        ISO 3166-1 alpha-2
 * @param contactEmail   the applicant's contact
 */
public record MembershipApplication(
        @NotBlank String applicationRef,
        @NotBlank String legalName,
        @NotBlank @Pattern(regexp = "DX-[0-9]{8}") String decadeXId,
        @NotBlank String did,
        @NotBlank @Pattern(regexp = "[A-Z]{2}") String country,
        @NotBlank @Email String contactEmail
) {
}
