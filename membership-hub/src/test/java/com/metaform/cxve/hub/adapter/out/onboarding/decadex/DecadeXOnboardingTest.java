package com.metaform.cxve.hub.adapter.out.onboarding.decadex;

import com.metaform.cxve.hub.adapter.out.onboarding.RegistrationValidator;
import com.metaform.cxve.hub.config.DataspaceProperties;
import com.metaform.cxve.hub.domain.model.MemberData;
import com.metaform.cxve.hub.domain.model.RegistrationOutcome;
import com.metaform.cxve.hub.domain.port.DataspaceOnboarding.InvalidRegistrationException;
import jakarta.validation.Validation;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The Decade-X half of a member request — deliberately unlike Catena-X's: a two-field registration
 * object, and decisions (APPROVED/REJECTED, keyed by the hub's applicationRef) instead of CX-0010
 * statuses.
 */
class DecadeXOnboardingTest {

    private final DecadeXOnboarding onboarding = new DecadeXOnboarding(
            new DataspaceProperties.Onboarding("http://dx-onboarding.test",
                    new DataspaceProperties.Client("http://idp.test/token", "hub", "secret", ""),
                    new DataspaceProperties.Callback("http://hub.test/api/callbacks/decade-x/registration-status",
                            "http://idp.test/token", "hub-callback", "secret")),
            new RegistrationValidator(Validation.buildDefaultValidatorFactory().getValidator()));

    private static MemberData member(Map<String, Object> registration) {
        return new MemberData("decade-x", "Acme Corp", "acme", "DX-00000001", null, registration);
    }

    @Test
    void validate_acceptsTheDecadeXRegistration() {
        onboarding.validate(member(Map.of("country", "DE", "contactEmail", "ops@acme.example")));
    }

    @Test
    void validate_refusesACatenaXRegistration() {
        // the request shape is per dataspace — a CX registration object does not pass for Decade-X
        assertThatThrownBy(() -> onboarding.validate(member(Map.of("city", "Munich", "country", "DE",
                "contactEmail", "ops@acme.example"))))
                .isInstanceOf(InvalidRegistrationException.class)
                .hasMessageContaining("city");
        assertThatThrownBy(() -> onboarding.validate(member(Map.of("country", "Germany"))))
                .isInstanceOf(InvalidRegistrationException.class)
                .hasMessageContaining("registration.contactEmail")
                .hasMessageContaining("registration.country");
    }

    @Test
    void issuerProperties_carryTheDecadeXId() {
        assertThat(onboarding.issuerProperties("did:web:acme",
                member(Map.of("country", "DE", "contactEmail", "ops@acme.example"))))
                .containsEntry("id", "did:web:acme")
                .containsEntry("memberOf", "Decade-X")
                .containsEntry("decadeXId", "DX-00000001")
                .containsEntry("bpn", "DX-00000001");
    }

    @Test
    void readCallback_mapsTheDecision() {
        assertThat(onboarding.readCallback(Map.of("applicationRef", "ext-1", "applicationId", "app-1",
                "decision", "APPROVED")))
                .isEqualTo(new RegistrationOutcome("ext-1", RegistrationOutcome.Status.CONFIRMED, null));
        assertThat(onboarding.readCallback(Map.of("applicationRef", "ext-1", "decision", "REJECTED",
                "reason", "Decade-X-ID taken")))
                .isEqualTo(new RegistrationOutcome("ext-1", RegistrationOutcome.Status.DECLINED, "Decade-X-ID taken"));
    }
}
