package com.metaform.dxonboarding.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * How submitted onboarding requests are reviewed ({@code dx-onboarding.review}). There is no TSP
 * operator in the VE yet, so a request is either approved automatically or waits — and which one
 * depends on where the participant is hosted:
 *
 * <ul>
 *   <li><b>hosted here</b> — a participant this VE hosts (the verification participant, the e2e
 *       tests' participants): its connector identity starts with {@code hostedDidPrefix}, the DID
 *       prefix the Membership Hub mints its participants' DIDs under ({@code participant.did.template});</li>
 *   <li><b>external</b> — any other participant, e.g. a system under test.</li>
 * </ul>
 *
 * @param hostedDidPrefix the DID prefix of participants hosted by this VE
 * @param autoApprove     which of the two are approved without an operator
 * @param delay           how long an automatic approval waits after the submission (0: right away,
 *                        still after the submitting call returned)
 */
@ConfigurationProperties(prefix = "dx-onboarding.review")
public record ReviewProperties(String hostedDidPrefix, AutoApprove autoApprove, Duration delay) {

    public ReviewProperties {
        autoApprove = autoApprove == null ? new AutoApprove(false, false) : autoApprove;
        delay = delay == null ? Duration.ZERO : delay;
    }

    /**
     * @param hosted   approve the requests of participants hosted here automatically
     * @param external approve every other participant's requests automatically — on FOR NOW;
     *                 switched off, they wait for a TSP operator's review
     */
    public record AutoApprove(boolean hosted, boolean external) {
    }

    /** Whether the connector identity belongs to a participant this VE hosts. */
    public boolean hostedHere(String connectorId) {
        return hostedDidPrefix != null && !hostedDidPrefix.isBlank() && connectorId != null
                && connectorId.startsWith(hostedDidPrefix);
    }
}
