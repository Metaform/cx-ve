package com.metaform.dxonboarding.domain.model.onboarding;

import java.util.UUID;

/** The applicant's acceptance of a versioned document — the General Terms and Conditions. */
public record ConsentDeclaration(UUID versionId, String versionNumber, Boolean accepted) {
}
