package com.metaform.cxve.hub.adapter.out.onboarding;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.metaform.cxve.hub.domain.model.MemberData;
import java.util.List;

/**
 * Wire shape of the Onboarding API's {@code PartnerRegistrationData} (CX-0009 §2.2.1), mirrored
 * locally (like the e2e suite does) so this app depends on the HTTP contract, not on the other
 * app's classes. Carries the spec-mandatory fields the hub populates — including the address and
 * initial-user data; the remaining optional fields are omitted entirely (NON_NULL
 * serialization). The hub's agreements are deliberately NOT forwarded: the current spec revision
 * removed them from the registration payload.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PartnerRegistrationPayload(
        String externalId,
        String name,
        String shortName,
        String bpn,
        String did,
        String city,
        String streetName,
        String countryAlpha2Code,
        String region,
        List<UniqueIdData> uniqueIds,
        List<String> companyRoles,
        List<UserDetailData> userDetails
) {

    public static PartnerRegistrationPayload from(String externalId, String did, MemberData data) {
        return new PartnerRegistrationPayload(
                externalId,
                data.name(),
                data.shortName(),
                data.bpn(),
                did,
                data.city(),
                data.streetName(),
                data.countryAlpha2Code(),
                data.region(),
                data.uniqueIds().stream().map(u -> new UniqueIdData(u.type(), u.value())).toList(),
                data.companyRoles(),
                data.userDetails().stream()
                        .map(u -> new UserDetailData(u.identityProviderId(), u.providerId(), u.username(),
                                u.firstName(), u.lastName(), u.email()))
                        .toList());
    }

    public record UniqueIdData(String type, String value) {
    }

    public record UserDetailData(String identityProviderId, String providerId, String username,
                                 String firstName, String lastName, String email) {
    }
}
