package com.metaform.cxve.hub.adapter.out.onboarding.catenax;

import com.metaform.cxve.hub.adapter.out.onboarding.RegistrationValidator;
import com.metaform.cxve.hub.config.DataspaceProperties;
import com.metaform.cxve.hub.domain.model.MemberData;
import com.metaform.cxve.hub.domain.model.RegistrationOutcome;
import com.metaform.cxve.hub.domain.port.DataspaceOnboarding.InvalidRegistrationException;
import jakarta.validation.Validation;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The Catena-X half of a member request: the {@code registration} object must be a complete
 * CX-0009 registration, only ACTIVE agreements make the member's {@code memberOf}, and the CX-0010
 * callback statuses map onto the hub's outcomes.
 */
class CatenaXOnboardingTest {

    private final CatenaXOnboarding onboarding = new CatenaXOnboarding(
            new DataspaceProperties.Onboarding("http://cx-onboarding.test",
                    new DataspaceProperties.Client("http://idp.test/token", "hub", "secret", "configure_partner_registration"),
                    new DataspaceProperties.Callback("http://hub.test/api/callbacks/catena-x/registration-status",
                            "http://idp.test/token", "hub-callback", "secret")),
            new RegistrationValidator(Validation.buildDefaultValidatorFactory().getValidator()));

    private static Map<String, Object> registration() {
        return Map.of(
                "city", "Munich", "streetName", "Otto-Hahn-Ring", "countryAlpha2Code", "DE", "region", "BY",
                "uniqueIds", List.of(Map.of("type", "VAT_ID", "value", "DE987654321")),
                "companyRoles", List.of("ACTIVE_PARTICIPANT"),
                "agreements", List.of(
                        Map.of("agreementId", "Catena-X", "consentStatus", "ACTIVE"),
                        Map.of("agreementId", "Other", "consentStatus", "INACTIVE")),
                "userDetails", List.of(Map.of("providerId", "prov-1", "firstName", "Jane", "lastName", "Doe",
                        "email", "jane.doe@acme.example")));
    }

    private static MemberData member(Map<String, Object> registration) {
        return new MemberData("catena-x", "Acme Corp", "acme", "BPNL0000000000XY", null, registration);
    }

    @Test
    void validate_acceptsACompleteRegistration() {
        onboarding.validate(member(registration()));
    }

    @Test
    void validate_namesEveryMissingField() {
        var incomplete = new HashMap<>(registration());
        incomplete.remove("city");
        incomplete.put("userDetails", List.of(Map.of("providerId", "prov-1")));

        assertThatThrownBy(() -> onboarding.validate(member(incomplete)))
                .isInstanceOf(InvalidRegistrationException.class)
                .hasMessageContaining("registration.city")
                .hasMessageContaining("registration.userDetails[0].email");
    }

    @Test
    void validate_refusesAnAbsentRegistration() {
        assertThatThrownBy(() -> onboarding.validate(member(null)))
                .isInstanceOf(InvalidRegistrationException.class)
                .hasMessageContaining("registration.companyRoles");
    }

    @Test
    void validate_refusesFieldsCatenaXDoesNotKnow() {
        // A payload meant for another dataspace must not slip through half-read.
        var foreign = new HashMap<>(registration());
        foreign.put("memberNumber", "DX-1");

        assertThatThrownBy(() -> onboarding.validate(member(foreign)))
                .isInstanceOf(InvalidRegistrationException.class)
                .hasMessageContaining("memberNumber");
    }

    @Test
    void issuerProperties_carryTheActiveAgreementsAndTheBpn() {
        assertThat(onboarding.issuerProperties("did:web:acme", member(registration())))
                .containsEntry("id", "did:web:acme")
                .containsEntry("memberOf", "Catena-X")
                .containsEntry("bpn", "BPNL0000000000XY")
                .containsEntry("contractVersion", "1.0");
    }

    @Test
    void readCallback_mapsTheApplicationStatus() {
        assertThat(onboarding.readCallback(Map.of("externalId", "ext-1", "applicationStatus", "CONFIRMED",
                "bpnl", "BPNL0000000000XY")))
                .isEqualTo(new RegistrationOutcome("ext-1", RegistrationOutcome.Status.CONFIRMED, null));
        assertThat(onboarding.readCallback(Map.of("externalId", "ext-1", "applicationStatus", "declined",
                "message", "duplicate")))
                .isEqualTo(new RegistrationOutcome("ext-1", RegistrationOutcome.Status.DECLINED, "duplicate"));
        assertThat(onboarding.readCallback(Map.of("externalId", "ext-1", "applicationStatus", "SUBMITTED")).status())
                .isEqualTo(RegistrationOutcome.Status.PENDING);
    }
}
