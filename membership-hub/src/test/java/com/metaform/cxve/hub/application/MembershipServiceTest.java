package com.metaform.cxve.hub.application;

import com.metaform.cxve.hub.adapter.out.persistence.InMemoryMembershipRepository;
import com.metaform.cxve.hub.domain.model.MemberData;
import com.metaform.cxve.hub.domain.model.Membership;
import com.metaform.cxve.hub.domain.model.MembershipState;
import com.metaform.cxve.hub.domain.port.OnboardingApi;
import com.metaform.cxve.hub.domain.port.TenantManager;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

class MembershipServiceTest {

    private static final String DID_TEMPLATE = "did:web:identity.test:";

    /** The in-memory store, remembering the minted external id so tests can find failed records. */
    private static class TrackingRepository extends InMemoryMembershipRepository {
        String lastCreatedExternalId;

        @Override
        public void create(Membership membership, MemberData payload) {
            lastCreatedExternalId = membership.externalId();
            super.create(membership, payload);
        }
    }

    private final TrackingRepository repository = new TrackingRepository();

    /** Records calls; can be told to fail, and to run a hook mid-submit (the callback race). */
    private static class RecordingOnboardingApi implements OnboardingApi {
        final List<String> submittedExternalIds = new ArrayList<>();
        final List<String> submittedDids = new ArrayList<>();
        int callbackRegistrations;
        boolean failSubmission;
        Consumer<String> onSubmit = externalId -> { };

        @Override
        public void registerCallback() {
            callbackRegistrations++;
        }

        @Override
        public String submitRegistration(String externalId, String did, MemberData data) {
            if (failSubmission) {
                throw new RuntimeException("Onboarding API unreachable");
            }
            // Runs while the "HTTP call" is still in flight — the synchronous-callback race.
            onSubmit.accept(externalId);
            submittedExternalIds.add(externalId);
            submittedDids.add(did);
            return "process-" + externalId;
        }
    }

    /** Deploys with a configurable context id; records what it was handed. */
    private static class RecordingTenantManager implements TenantManager {
        final List<List<String>> deployedAgreements = new ArrayList<>();
        final List<Membership> deployed = new ArrayList<>();
        String contextIdOnDeploy;
        String contextIdOnRefresh;
        boolean error;
        boolean failDeployment;

        @Override
        public ProvisionedProfile deployParticipant(Membership membership, List<String> activeAgreementIds) {
            if (failDeployment) {
                throw new RuntimeException("Tenant Manager unreachable");
            }
            deployed.add(membership);
            deployedAgreements.add(activeAgreementIds);
            return new ProvisionedProfile("tenant-1", "profile-1", contextIdOnDeploy, error);
        }

        @Override
        public ProvisionedProfile refresh(Membership membership) {
            return new ProvisionedProfile(membership.tenantId(), membership.participantProfileId(),
                    contextIdOnRefresh, error);
        }
    }

    private final RecordingOnboardingApi onboardingApi = new RecordingOnboardingApi();
    private final RecordingTenantManager tenantManager = new RecordingTenantManager();
    private final MembershipService service =
            new MembershipService(repository, onboardingApi, tenantManager, DID_TEMPLATE);

    private static MemberData request(String did) {
        return new MemberData("Acme Corp", "Acme", "BPNL0000000000XY", did,
                List.of(new MemberData.UniqueId("VAT_ID", "DE123456789")),
                List.of("ACTIVE_PARTICIPANT"),
                List.of(new MemberData.AgreementConsent("agreement-1", "ACTIVE"),
                        new MemberData.AgreementConsent("agreement-2", "INACTIVE")));
    }

    @Test
    void onboard_registersCallbackAndSubmitsUnderTheResolvedDid() {
        var membership = service.onboard(request(null));

        assertThat(membership.state()).isEqualTo(MembershipState.REGISTERING);
        // No DID supplied -> template + short name, the same rule the Onboarding API applies.
        assertThat(membership.did()).isEqualTo(DID_TEMPLATE + "Acme");
        assertThat(membership.onboardingProcessId()).isEqualTo("process-" + membership.externalId());
        assertThat(onboardingApi.callbackRegistrations).isEqualTo(1);
        assertThat(onboardingApi.submittedExternalIds).containsExactly(membership.externalId());
        assertThat(onboardingApi.submittedDids).containsExactly(DID_TEMPLATE + "Acme");
        // The record is persisted and readable back.
        assertThat(service.get(membership.externalId())).isEqualTo(membership);
    }

    @Test
    void onboard_honorsACallerSuppliedDid() {
        var membership = service.onboard(request("did:web:acme.example.com"));

        assertThat(membership.did()).isEqualTo("did:web:acme.example.com");
        assertThat(onboardingApi.submittedDids).containsExactly("did:web:acme.example.com");
    }

    @Test
    void onboard_marksTheMembershipFailedWhenTheSubmissionFails() {
        onboardingApi.failSubmission = true;

        var thrown = catchThrowable(() -> service.onboard(request(null)));

        assertThat(thrown).hasMessage("Onboarding API unreachable");
        // The record survives for audit, terminally failed — not wedged in SUBMITTED.
        var stored = repository.findByExternalId(repository.lastCreatedExternalId).orElseThrow();
        assertThat(stored.state()).isEqualTo(MembershipState.FAILED);
        assertThat(stored.failureReason()).contains("Onboarding API unreachable");
    }

    @Test
    void confirmedCallback_provisionsWithOnlyTheActiveAgreements() {
        var membership = service.onboard(request(null));

        var updated = service.onRegistrationStatus(membership.externalId(), "CONFIRMED", null);

        assertThat(updated.state()).isEqualTo(MembershipState.PROVISIONING);
        assertThat(updated.tenantId()).isEqualTo("tenant-1");
        assertThat(updated.participantProfileId()).isEqualTo("profile-1");
        assertThat(updated.participantContextId()).isNull();
        // Only ACTIVE consents make it into the cfm.issuer memberOf property.
        assertThat(tenantManager.deployedAgreements).containsExactly(List.of("agreement-1"));
        assertThat(tenantManager.deployed.get(0).did()).isEqualTo(DID_TEMPLATE + "Acme");
    }

    @Test
    void confirmedCallback_completesImmediatelyWhenTheContextIdIsAlreadyThere() {
        tenantManager.contextIdOnDeploy = "pctx-1";
        var membership = service.onboard(request(null));

        var updated = service.onRegistrationStatus(membership.externalId(), "CONFIRMED", null);

        assertThat(updated.state()).isEqualTo(MembershipState.PROVISIONED);
        assertThat(updated.participantContextId()).isEqualTo("pctx-1");
    }

    @Test
    void confirmedCallback_marksTheMembershipFailedWhenProvisioningFails() {
        tenantManager.failDeployment = true;
        var membership = service.onboard(request(null));

        var updated = service.onRegistrationStatus(membership.externalId(), "CONFIRMED", null);

        assertThat(updated.state()).isEqualTo(MembershipState.FAILED);
        assertThat(updated.failureReason()).contains("Tenant Manager unreachable");
    }

    @Test
    void rejectedCallback_marksTheMembershipRejected() {
        var membership = service.onboard(request(null));

        var updated = service.onRegistrationStatus(membership.externalId(), "REJECTED", "duplicate BPN");

        assertThat(updated.state()).isEqualTo(MembershipState.REJECTED);
        assertThat(updated.failureReason()).isEqualTo("duplicate BPN");
        // No provisioning for a rejected registration.
        assertThat(tenantManager.deployed).isEmpty();
    }

    @Test
    void aCallbackArrivingMidSubmit_isNotRolledBack() {
        // The Onboarding API completes synchronously when nothing gates its flow, so the
        // CONFIRMED callback can arrive BEFORE the submitting HTTP call returns. The post-submit
        // bookkeeping must keep that progress (and still record the process id).
        tenantManager.contextIdOnDeploy = "pctx-race";
        onboardingApi.onSubmit = externalId ->
                service.onRegistrationStatus(externalId, "CONFIRMED", null);

        var membership = service.onboard(request(null));

        assertThat(membership.state()).isEqualTo(MembershipState.PROVISIONED);
        assertThat(membership.participantContextId()).isEqualTo("pctx-race");
        assertThat(membership.onboardingProcessId()).isEqualTo("process-" + membership.externalId());
    }

    @Test
    void get_refreshesAProvisioningMembership() {
        var membership = service.onboard(request(null));
        service.onRegistrationStatus(membership.externalId(), "CONFIRMED", null);

        // Context id not there yet: still PROVISIONING.
        assertThat(service.get(membership.externalId()).state()).isEqualTo(MembershipState.PROVISIONING);

        // Once the platform assigns it, the next read completes the membership.
        tenantManager.contextIdOnRefresh = "pctx-9";
        var refreshed = service.get(membership.externalId());
        assertThat(refreshed.state()).isEqualTo(MembershipState.PROVISIONED);
        assertThat(refreshed.participantContextId()).isEqualTo("pctx-9");
        // The refreshed state is persisted, not just returned.
        assertThat(repository.findByExternalId(membership.externalId()).orElseThrow().state())
                .isEqualTo(MembershipState.PROVISIONED);
    }

    @Test
    void get_failsAProvisioningMembershipWhoseProfileReportsAnError() {
        var membership = service.onboard(request(null));
        service.onRegistrationStatus(membership.externalId(), "CONFIRMED", null);
        tenantManager.error = true;

        assertThat(service.get(membership.externalId()).state()).isEqualTo(MembershipState.FAILED);
    }

    @Test
    void aLateCallback_doesNotDisturbATerminalMembership() {
        tenantManager.contextIdOnDeploy = "pctx-1";
        var membership = service.onboard(request(null));
        service.onRegistrationStatus(membership.externalId(), "CONFIRMED", null);
        assertThat(service.get(membership.externalId()).state()).isEqualTo(MembershipState.PROVISIONED);

        // A redelivered/late callback must not re-provision or overwrite the outcome.
        var after = service.onRegistrationStatus(membership.externalId(), "CONFIRMED", null);

        assertThat(after.state()).isEqualTo(MembershipState.PROVISIONED);
        assertThat(tenantManager.deployed).hasSize(1);
    }

    @Test
    void anUnknownStatus_changesNothing() {
        var membership = service.onboard(request(null));

        var after = service.onRegistrationStatus(membership.externalId(), "SUBMITTED", null);

        assertThat(after.state()).isEqualTo(MembershipState.REGISTERING);
        assertThat(tenantManager.deployed).isEmpty();
    }
}
