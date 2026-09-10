package com.metaform.cxve.verification;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * The Verification UI: an Angular dashboard plus the backend-for-frontend it talks to. The BFF
 * drives the full participant verification journey against a running VE — onboarding a
 * participant-under-test through the Membership Hub, establishing the CCM data flows via the EDC
 * management API, exchanging a certificate via Certo (CX-0135 Flow B), and judging the run
 * against the compliance tracker's eventlog. The browser only ever talks to this app; all
 * platform tokens (jwtlet workload-token exchange) stay server-side.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class VerificationUiApplication {

    public static void main(String[] args) {
        SpringApplication.run(VerificationUiApplication.class, args);
    }
}
