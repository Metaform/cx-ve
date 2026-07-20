package com.beardyinc.cxve.model;

import java.util.UUID;

public record AgreementConsentData(
        UUID agreementId,
        ConsentStatusId consentStatus
) {
}
