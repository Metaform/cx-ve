package com.metaform.dxonboarding;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * The Decade-X onboarding API: the TSP (Trust Service Provider) onboarding intake, which a
 * participant reaches as an asset of the federated connector. An onboarding request is submitted
 * with its documents in one step and read back with its status.
 *
 * <p>In memory only, and without the TSP operator's review: a request stays {@code SUBMITTED}. It
 * does NOT register credential holders or have credentials offered — there is no Decade-X issuer
 * setup in the VE yet.
 */
@SpringBootApplication
public class DxOnboardingApplication {

    public static void main(String[] args) {
        SpringApplication.run(DxOnboardingApplication.class, args);
    }
}
