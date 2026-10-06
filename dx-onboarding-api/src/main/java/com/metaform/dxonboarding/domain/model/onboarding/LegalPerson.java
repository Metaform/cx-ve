package com.metaform.dxonboarding.domain.model.onboarding;

import java.time.LocalDate;

/**
 * The applicant's legal representative.
 *
 * @param powerOfAttorneyValidUntil when the power of attorney (a document of the submission) expires
 */
public record LegalPerson(
        String fullName,
        String email,
        String role,
        String phoneNumber,
        Address headquartersAddress,
        LocalDate powerOfAttorneyValidUntil) {
}
