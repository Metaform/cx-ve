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
import org.junit.jupiter.api.BeforeEach;
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

    /**
     * Records calls; can be told to fail, and runs a hook while the "HTTP call" is in flight —
     * which is where the Onboarding API's synchronous status callback lands in production.
     */
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
            onSubmit.accept(externalId);
            submittedExternalIds.add(externalId);
            submittedDids.add(did);
            return "process-" + externalId;
        }
    }

    /** Deploys with a configurable context id; records what it was handed and how often refreshed. */
    private static class RecordingTenantManager implements TenantManager {
        final List<List<String>> deployedAgreements = new ArrayList<>();
        final List<Membership> deployed = new ArrayList<>();
        String contextIdOnDeploy;
        String contextIdOnRefresh;
        int refreshCount;
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
            refreshCount++;
            return new ProvisionedProfile(membership.cfmTenantId(), membership.cfmParticipantProfileId(),
                    contextIdOnRefresh, error);
        }
    }

    private final RecordingOnboardingApi onboardingApi = new RecordingOnboardingApi();
    private final RecordingTenantManager tenantManager = new RecordingTenantManager();
    private final MembershipService service =
            new MembershipService(repository, onboardingApi, tenantManager, DID_TEMPLATE);

    @BeforeEach
    void confirmSynchronously() {
        // The production Onboarding API runs the registration to completion inside the submitting
        // call and delivers the CONFIRMED callback before it returns — the default here mirrors
        // that. Tests for the other outcomes override the hook.
        onboardingApi.onSubmit = externalId -> service.onRegistrationStatus(externalId, "CONFIRMED", null);
    }

    private static MemberData request(String did) {
        return new MemberData("Acme Corp", "Acme", "BPNL0000000000XY", did,
                List.of(new MemberData.UniqueId("VAT_ID", "DE123456789")),
                List.of("ACTIVE_PARTICIPANT"),
                List.of(new MemberData.AgreementConsent("agreement-1", "ACTIVE"),
                        new MemberData.AgreementConsent("agreement-2", "INACTIVE")));
    }

    /** The DID the resolver derives for {@link #request}'s short name. */
    private static final String ACME_DID = DID_TEMPLATE + "Acme";

    @Test
    void onboard_registersSubmitsAndProvisionsInOneCall() {
        var membership = service.onboard(request(null));

        // Registration confirmed within the call, so the profile is deployed right away; the
        // context id is not there yet — GET picks it up later.
        assertThat(membership.state()).isEqualTo(MembershipState.PROVISIONING);
        assertThat(membership.did()).isEqualTo(ACME_DID);
        assertThat(membership.onboardingProcessId()).isEqualTo("process-" + membership.externalId());
        assertThat(membership.cfmTenantId()).isEqualTo("tenant-1");
        assertThat(membership.cfmParticipantProfileId()).isEqualTo("profile-1");
        assertThat(membership.edcParticipantContextId()).isNull();
        assertThat(onboardingApi.callbackRegistrations).isEqualTo(1);
        assertThat(onboardingApi.submittedExternalIds).containsExactly(membership.externalId());
        assertThat(onboardingApi.submittedDids).containsExactly(ACME_DID);
        // Only ACTIVE consents make it into the cfm.issuer memberOf property, deployed under the
        // resolved DID.
        assertThat(tenantManager.deployedAgreements).containsExactly(List.of("agreement-1"));
        assertThat(tenantManager.deployed.get(0).did()).isEqualTo(ACME_DID);
    }

    @Test
    void onboard_completesImmediatelyWhenTheDeployResponseCarriesTheContextId() {
        tenantManager.contextIdOnDeploy = "pctx-1";

        var membership = service.onboard(request(null));

        assertThat(membership.state()).isEqualTo(MembershipState.PROVISIONED);
        assertThat(membership.edcParticipantContextId()).isEqualTo("pctx-1");
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
        assertThat(tenantManager.deployed).isEmpty();
    }

    @Test
    void onboard_doesNotProvisionARejectedRegistration() {
        // The Onboarding API answers a rejected registration with a normal 200 too — the callback
        // is what carries the outcome, and no EDC resources may be provisioned on a rejection.
        onboardingApi.onSubmit = externalId ->
                service.onRegistrationStatus(externalId, "REJECTED", "duplicate BPN");

        var membership = service.onboard(request(null));

        assertThat(membership.state()).isEqualTo(MembershipState.REJECTED);
        assertThat(membership.failureReason()).isEqualTo("duplicate BPN");
        // The process id of the rejected onboarding is still recorded for audit.
        assertThat(membership.onboardingProcessId()).isEqualTo("process-" + membership.externalId());
        assertThat(tenantManager.deployed).isEmpty();
    }

    @Test
    void onboard_doesNotProvisionWithoutAConfirmation() {
        // No callback within the submitting call (registration gated on something asynchronous,
        // or callbacks broken): the membership surfaces as REGISTERING and nothing is deployed.
        onboardingApi.onSubmit = externalId -> { };

        var membership = service.onboard(request(null));

        assertThat(membership.state()).isEqualTo(MembershipState.REGISTERING);
        assertThat(tenantManager.deployed).isEmpty();
    }

    @Test
    void onboard_marksTheMembershipFailedWhenProvisioningFails() {
        tenantManager.failDeployment = true;

        var membership = service.onboard(request(null));

        // The registration side is done — the provisioning failure is recorded terminally on the
        // record rather than thrown, so the caller still receives the membership.
        assertThat(membership.state()).isEqualTo(MembershipState.FAILED);
        assertThat(membership.failureReason()).contains("Tenant Manager unreachable");
    }

    @Test
    void get_readsTheProfileThroughTheStoredIdUntilTheContextIdAppears() {
        var membership = service.onboard(request(null));
        assertThat(membership.state()).isEqualTo(MembershipState.PROVISIONING);

        // Context id not there yet: still PROVISIONING, read through the stored profile id.
        assertThat(service.get(membership.externalId()).state()).isEqualTo(MembershipState.PROVISIONING);
        assertThat(tenantManager.refreshCount).isEqualTo(1);

        // Once the platform assigns it, the next read completes the membership — persisted, not
        // just returned.
        tenantManager.contextIdOnRefresh = "pctx-9";
        var refreshed = service.get(membership.externalId());
        assertThat(refreshed.state()).isEqualTo(MembershipState.PROVISIONED);
        assertThat(refreshed.edcParticipantContextId()).isEqualTo("pctx-9");
        assertThat(repository.findByExternalId(membership.externalId()).orElseThrow().state())
                .isEqualTo(MembershipState.PROVISIONED);

        // A terminal membership is no longer read through the Tenant Manager.
        var countAfterCompletion = tenantManager.refreshCount;
        service.get(membership.externalId());
        assertThat(tenantManager.refreshCount).isEqualTo(countAfterCompletion);
    }

    @Test
    void get_failsAMembershipWhoseProfileReportsAnError() {
        var membership = service.onboard(request(null));
        tenantManager.error = true;

        assertThat(service.get(membership.externalId()).state()).isEqualTo(MembershipState.FAILED);
    }

    @Test
    void get_returnsAMembershipWithoutAProfileAsStored() {
        onboardingApi.onSubmit = externalId -> { };
        var membership = service.onboard(request(null));

        assertThat(service.get(membership.externalId()).state()).isEqualTo(MembershipState.REGISTERING);
        assertThat(tenantManager.refreshCount).isZero();
    }

    @Test
    void aLateCallback_doesNotDisturbATerminalMembership() {
        tenantManager.contextIdOnDeploy = "pctx-1";
        var membership = service.onboard(request(null));
        assertThat(membership.state()).isEqualTo(MembershipState.PROVISIONED);

        // A redelivered/late callback must not re-provision or overwrite the outcome.
        var after = service.onRegistrationStatus(membership.externalId(), "CONFIRMED", null);

        assertThat(after.state()).isEqualTo(MembershipState.PROVISIONED);
        assertThat(tenantManager.deployed).hasSize(1);
    }

    @Test
    void anUnknownStatus_changesNothing() {
        onboardingApi.onSubmit = externalId ->
                service.onRegistrationStatus(externalId, "SUBMITTED", null);

        var membership = service.onboard(request(null));

        assertThat(membership.state()).isEqualTo(MembershipState.REGISTERING);
        assertThat(tenantManager.deployed).isEmpty();
    }
}
