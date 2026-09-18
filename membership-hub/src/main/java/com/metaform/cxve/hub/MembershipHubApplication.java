package com.metaform.cxve.hub;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * The Catena-X Membership Hub: drives a partner's full path into the dataspace by combining the
 * two halves the platform deliberately keeps apart — the CX-0006 registration (Onboarding API)
 * and the EDC resource provisioning (CFM Tenant Manager). It provisions a hosted partner's EDC
 * resources FIRST and registers it as an onboarding service provider second — the registration
 * ends with the issuer pushing a credential offer to the partner's wallet, which must exist by
 * then; the membership record correlates the registration's {@code externalId} with the
 * {@code participantContextId} the provisioning yields.
 */
@SpringBootApplication
public class MembershipHubApplication {

    public static void main(String[] args) {
        SpringApplication.run(MembershipHubApplication.class, args);
    }
}
