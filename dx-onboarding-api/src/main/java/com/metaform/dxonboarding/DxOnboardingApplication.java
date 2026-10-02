package com.metaform.dxonboarding;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * The Decade-X onboarding API: the TSP (Trust Service Provider) onboarding intake, which a
 * participant reaches as an asset of the federated connector. An onboarding request is submitted
 * with its documents in one step and read back with its status.
 *
 * <p>In memory only, and without a TSP operator: a request is approved automatically or waits in
 * {@code SUBMITTED} — the requests of participants hosted by the VE are approved, and for now those
 * of external participants too ({@code dx-onboarding.review}). An approval does NOT register a
 * credential holder or have credentials offered — there is no Decade-X issuer setup in the VE yet.
 */
@SpringBootApplication
public class DxOnboardingApplication {

    public static void main(String[] args) {
        SpringApplication.run(DxOnboardingApplication.class, args);
    }
}
