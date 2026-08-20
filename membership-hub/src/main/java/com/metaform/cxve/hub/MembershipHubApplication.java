package com.metaform.cxve.hub;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * The Catena-X Membership Hub: drives a partner's full path into the dataspace by combining the
 * two halves the platform deliberately keeps apart — the CX-0006 registration (Onboarding API)
 * and the EDC resource provisioning (CFM Tenant Manager). It submits the registration as an
 * onboarding service provider, waits for the CONFIRMED status callback, and only then provisions
 * the participant's EDC resources; the membership record correlates the registration's
 * {@code externalId} with the {@code participantContextId} the provisioning yields.
 */
@SpringBootApplication
public class MembershipHubApplication {

    public static void main(String[] args) {
        SpringApplication.run(MembershipHubApplication.class, args);
    }
}
