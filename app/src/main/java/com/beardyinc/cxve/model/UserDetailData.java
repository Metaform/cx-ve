package com.beardyinc.cxve.model;

import java.util.UUID;

public record UserDetailData(
        String identityProviderId,
        String providerId,
        String username,
        String firstName,
        String lastName,
        String email
) {
}
