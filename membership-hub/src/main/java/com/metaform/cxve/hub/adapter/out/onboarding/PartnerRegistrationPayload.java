package com.metaform.cxve.hub.adapter.out.onboarding;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.metaform.cxve.hub.domain.model.MemberData;
import java.util.List;

/**
 * Wire shape of the Onboarding API's {@code PartnerRegistrationData}, mirrored locally (like the
 * e2e suite does) so this app depends on the HTTP contract, not on the other app's classes. Only
 * the fields the hub populates are declared; the API's optional address fields are omitted
 * entirely (NON_NULL serialization).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PartnerRegistrationPayload(
        String name,
        String shortName,
        String bpn,
        String did,
        String externalId,
        List<UniqueIdData> uniqueIds,
        List<String> companyRoles,
        List<AgreementConsentData> agreements
) {

    public static PartnerRegistrationPayload from(String externalId, String did, MemberData data) {
        return new PartnerRegistrationPayload(
                data.name(),
                data.shortName(),
                data.bpn(),
                did,
                externalId,
                data.uniqueIds().stream().map(u -> new UniqueIdData(u.type(), u.value())).toList(),
                data.companyRoles(),
                data.agreements().stream()
                        .map(a -> new AgreementConsentData(a.agreementId(), a.consentStatus()))
                        .toList());
    }

    public record UniqueIdData(String type, String value) {
    }

    public record AgreementConsentData(String agreementId, String consentStatus) {
    }
}
