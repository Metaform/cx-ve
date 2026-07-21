package com.beardyinc.cxve.domain.model;

public record UserDetailData(
        String identityProviderId,
        String providerId,
        String username,
        String firstName,
        String lastName,
        String email
) {
}
