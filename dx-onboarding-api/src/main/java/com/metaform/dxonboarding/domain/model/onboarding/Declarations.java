package com.metaform.dxonboarding.domain.model.onboarding;

/** What the applicant declares by submitting; all of it must be affirmed. */
public record Declarations(Boolean informationAccurate, Boolean authorisedToAct, Boolean evidenceMayBeRequested) {
}
