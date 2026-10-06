package com.metaform.cxve.verification.application;

import com.metaform.cxve.verification.adapter.out.hub.MembershipHubClient;
import com.metaform.cxve.verification.domain.model.RunStep;
import com.metaform.cxve.verification.domain.model.VerificationRun;
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

        assertThat(catalog().resolve("catena-x", "ccm", "BPNLACME00000001")).isSameAs(ccm);
    }

    @Test
    void resolve_refusesWhatCannotBeVerified() {
        when(hub.servedDataspaces()).thenReturn(List.of("catena-x"));
        var catalog = catalog();

        assertThatThrownBy(() -> catalog.resolve("decade-x", "ccm", "DX-1"))
                .isInstanceOf(DataspaceCatalog.InvalidRunRequestException.class).hasMessageContaining("decade-x");
        assertThatThrownBy(() -> catalog.resolve("catena-x", "traceability", "BPNLACME00000001"))
                .isInstanceOf(DataspaceCatalog.InvalidRunRequestException.class).hasMessageContaining("Traceability");
        assertThatThrownBy(() -> catalog.resolve("catena-x", "ccm", "ACME"))
                .isInstanceOf(DataspaceCatalog.InvalidRunRequestException.class).hasMessageContaining("BPN");
    }

    @Test
    void resolve_refusesADataspaceTheHubDoesNotServe() {
        when(hub.servedDataspaces()).thenReturn(List.of());

        assertThatThrownBy(() -> catalog().resolve("catena-x", "ccm", "BPNLACME00000001"))
                .isInstanceOf(DataspaceCatalog.InvalidRunRequestException.class)
                .hasMessageContaining("Membership Hub");
    }
}
