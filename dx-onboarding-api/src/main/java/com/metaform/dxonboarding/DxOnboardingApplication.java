package com.metaform.dxonboarding;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * The DECADE-X onboarding API: the TSP (Trust Service Provider) onboarding intake, which a
 * participant reaches as an asset of the federated connector. An onboarding request is submitted
 * with its documents in one step and read back with its status.
 *
 * <p>In memory only, and without a TSP operator: a request is approved automatically or waits in
 * {@code SUBMITTED} — the requests of participants hosted by the VE are approved, and for now those
 * of external participants too ({@code dx-onboarding.review}). An approval makes the applicant a
 * member the way the Catena-X onboarding API does: it gets its DECADE-X-ID, is registered as a
 * credential holder with the platform's IssuerService, and is offered the
 * DecadeXMembershipCredential ({@link com.metaform.dxonboarding.application.ApprovalService}).
 */
@SpringBootApplication
public class DxOnboardingApplication {

    public static void main(String[] args) {
        SpringApplication.run(DxOnboardingApplication.class, args);
    }
}
