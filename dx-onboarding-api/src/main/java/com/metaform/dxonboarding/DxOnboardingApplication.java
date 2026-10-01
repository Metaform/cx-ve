package com.metaform.dxonboarding;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * A STUB of the Decade-X onboarding API: the second dataspace's registration, deliberately shaped
 * differently from the Catena-X one (its own endpoints, payload and webhook format), so the
 * Membership Hub's per-dataspace onboarding is exercised against something other than CX-0009.
 *
 * <p>It approves every well-formed application that does not collide with an earlier one and
 * reports the decision through the registered webhook. It does NOT register credential holders or
 * have credentials offered — there is no Decade-X issuer setup in the VE yet — so an approval here
 * says less than a Catena-X confirmation does.
 */
@SpringBootApplication
public class DxOnboardingApplication {

    public static void main(String[] args) {
        SpringApplication.run(DxOnboardingApplication.class, args);
    }
}
