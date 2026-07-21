package com.beardyinc.cxve.domain.model;

public record AgreementConsentData(
        String agreementId,
        ConsentStatusId consentStatus
) {
}
