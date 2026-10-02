package com.metaform.cxve.verification.application;

import com.metaform.cxve.verification.adapter.out.hub.MembershipHubClient;
import com.metaform.cxve.verification.domain.model.RunStep;
import com.metaform.cxve.verification.domain.model.VerificationRun;
import com.metaform.cxve.verification.config.VerificationProperties;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

/**
 * What can be verified: a dataspace only while the hub serves it, a use case only when it is
 * enabled AND implemented — and everything configured is listed either way, so the UI can show
 * what exists but cannot be picked.
 */
@ExtendWith(MockitoExtension.class)
class DataspaceCatalogTest {

    @Mock
    private MembershipHubClient hub;

    private final UseCaseFlow ccm = new UseCaseFlow() {
        @Override
        public String useCase() {
            return "ccm";
        }

        @Override
        public List<RunStep> steps(boolean externallyHosted) {
            return RunStep.MANAGED;
        }

        @Override
        public void execute(VerificationRun run) {
        }
    };

    private DataspaceCatalog catalog() {
        return new DataspaceCatalog(TestFixtures.props(Map.of()), hub, List.of(ccm));
    }

    @Test
    void list_marksAServedDataspacesImplementedUseCasesAvailable() {
        when(hub.servedDataspaces()).thenReturn(List.of("catena-x"));

        var dataspaces = catalog().list();

        assertThat(dataspaces).hasSize(1);
        var catenaX = dataspaces.get(0);
        assertThat(catenaX.available()).isTrue();
        assertThat(catenaX.memberId().label()).isEqualTo("BPN");
        assertThat(catenaX.useCases())
                .extracting(DataspaceCatalog.UseCase::id, DataspaceCatalog.UseCase::available)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple("ccm", true),
                        // listed, but disabled in the profile
                        org.assertj.core.groups.Tuple.tuple("traceability", false));
    }

    @Test
    void list_showsADataspaceTheHubDoesNotServe_asUnavailable() {
        when(hub.servedDataspaces()).thenReturn(List.of());

        var catenaX = catalog().list().get(0);

        assertThat(catenaX.available()).isFalse();
        assertThat(catenaX.useCases()).noneMatch(DataspaceCatalog.UseCase::available);
    }

    @Test
    void resolve_returnsTheUseCasesFlow() {
        when(hub.servedDataspaces()).thenReturn(List.of("catena-x"));

        assertThat(catalog().resolve("catena-x", "ccm", "BPNLACME00000001", false)).isSameAs(ccm);
    }

    @Test
    void resolve_refusesWhatCannotBeVerified() {
        when(hub.servedDataspaces()).thenReturn(List.of("catena-x"));
        var catalog = catalog();

        assertThatThrownBy(() -> catalog.resolve("decade-x", "ccm", "DX-1", false))
                .isInstanceOf(DataspaceCatalog.InvalidRunRequestException.class).hasMessageContaining("decade-x");
        assertThatThrownBy(() -> catalog.resolve("catena-x", "traceability", "BPNLACME00000001", false))
                .isInstanceOf(DataspaceCatalog.InvalidRunRequestException.class).hasMessageContaining("Traceability");
        assertThatThrownBy(() -> catalog.resolve("catena-x", "ccm", "ACME", false))
                .isInstanceOf(DataspaceCatalog.InvalidRunRequestException.class).hasMessageContaining("BPN");
    }

    @Test
    void resolve_refusesADataspaceTheHubDoesNotServe() {
        when(hub.servedDataspaces()).thenReturn(List.of());

        assertThatThrownBy(() -> catalog().resolve("catena-x", "ccm", "BPNLACME00000001", false))
                .isInstanceOf(DataspaceCatalog.InvalidRunRequestException.class)
                .hasMessageContaining("Membership Hub");
    }

    @Test
    void resolve_requiresTheMemberId() {
        when(hub.servedDataspaces()).thenReturn(List.of("catena-x"));
        var catalog = catalog();

        assertThatThrownBy(() -> catalog.resolve("catena-x", "ccm", null, false))
                .isInstanceOf(DataspaceCatalog.InvalidRunRequestException.class).hasMessageContaining("BPN is required");
        // Catena-X does not assign an external participant's BPN: it is agreed with its operator
        assertThatThrownBy(() -> catalog.resolve("catena-x", "ccm", null, true))
                .isInstanceOf(DataspaceCatalog.InvalidRunRequestException.class).hasMessageContaining("BPN is required");
    }

    @Test
    void resolve_aDataspaceThatAssignsIt_takesNoMemberIdForAnExternalParticipant() {
        var props = TestFixtures.props(Map.of());
        var catenaX = props.dataspace("catena-x");
        var assigning = new VerificationProperties.DataspaceProfile("Assigning",
                new VerificationProperties.MemberId("Decade-X-ID", "DX-[0-9]{8}", "DX-00000001", true),
                catenaX.dspProfile(), catenaX.policyContext(), catenaX.accessConstraints(),
                catenaX.contractConstraints(), catenaX.registrationTemplate(),
                new VerificationProperties.ParticipantIdentity("VP", "vp-dx", "DX-99999999", "DXVERIFY0001"),
                catenaX.useCases());
        var catalog = new DataspaceCatalog(new VerificationProperties(props.dspBaseUrl(), props.management(),
                props.certoAuth(), props.transferType(), props.credentialDeliverySubject(), props.timeouts(),
                props.pollInterval(), props.external(), Map.of("assigning", assigning)), hub, List.of(ccm));
        when(hub.servedDataspaces()).thenReturn(List.of("assigning"));

        assertThat(catalog.resolve("assigning", "ccm", null, true)).isSameAs(ccm);
        assertThatThrownBy(() -> catalog.resolve("assigning", "ccm", "DX-00000001", true))
                .isInstanceOf(DataspaceCatalog.InvalidRunRequestException.class).hasMessageContaining("leave it empty");
        // a participant hosted here brings its own: its deployment needs it
        assertThat(catalog.resolve("assigning", "ccm", "DX-00000001", false)).isSameAs(ccm);
        assertThatThrownBy(() -> catalog.resolve("assigning", "ccm", null, false))
                .isInstanceOf(DataspaceCatalog.InvalidRunRequestException.class).hasMessageContaining("required");
    }
}
