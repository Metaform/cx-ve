package com.metaform.dxonboarding.domain.model.onboarding;

import java.util.UUID;

/**
 * The applicant's acceptance of a use case agreement (UCA). Its signed document is the
 * submission's {@code ucaDocument[<useCaseId>]}. The reviewer-only fields of the wire format
 * ({@code verified}, {@code verificationNotes}) are not modelled, so they are ignored when sent.
 */
public record UseCaseAgreement(
        String useCaseId,
        String useCaseName,
        UUID versionId,
        String versionNumber,
        Boolean accepted) {
}
