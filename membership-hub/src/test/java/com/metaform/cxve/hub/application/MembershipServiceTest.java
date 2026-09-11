package com.metaform.cxve.hub.application;

import com.metaform.cxve.hub.adapter.out.persistence.InMemoryMembershipRepository;
import com.metaform.cxve.hub.domain.model.MemberData;
import com.metaform.cxve.hub.domain.model.Membership;
import com.metaform.cxve.hub.domain.model.MembershipState;
import com.metaform.cxve.hub.domain.port.OnboardingApi;
import com.metaform.cxve.hub.domain.port.TenantManager;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * The asynchronous choreography: {@code onboard} only submits, the status callback drives the
 * outcome, CONFIRMED triggers provisioning on the worker. The worker executor is swappable per
 * test — direct execution for determinism, a dropping executor to simulate a crash between
 * confirmation and claim.
 */
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
     * where the current Onboarding API's synchronous status callback would land in production.
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
            return new ProvisionedProfile(membership.tenantId(), membership.participantProfileId(),
                    contextIdOnRefresh, error);
        }
    }

    private final RecordingOnboardingApi onboardingApi = new RecordingOnboardingApi();
    private final RecordingTenantManager tenantManager = new RecordingTenantManager();
    // swappable per test; the service holds the indirection, not the executor itself
    private Executor provisioningExecutor = Runnable::run;
    private final MembershipService service = new MembershipService(repository, onboardingApi, tenantManager,
            DID_TEMPLATE, task -> provisioningExecutor.execute(task));

    private static MemberData request(String did) {
        return new MemberData("Acme Corp", "Acme", "BPNL0000000000XY",
                "Berlin", "Musterstrasse", "DE", "BE", did,
                List.of(new MemberData.UniqueId("VAT_ID", "DE123456789")),
                List.of("ACTIVE_PARTICIPANT"),
                List.of(new MemberData.AgreementConsent("agreement-1", "ACTIVE"),
                        new MemberData.AgreementConsent("agreement-2", "INACTIVE")),
                List.of(new MemberData.UserDetail(null, "prov-1", "jdoe", "John", "Doe", "john.doe@acme.example")));
    }

    /** The DID the resolver derives for {@link #request}'s short name. */
    private static final String ACME_DID = DID_TEMPLATE + "Acme";

    private Membership stored(String externalId) {
        return repository.findByExternalId(externalId).orElseThrow();
    }

    @Test
    void onboard_submitsAndReturnsWithoutWaitingForTheOutcome() {
        var membership = service.onboard(request(null));

        // No callback yet: the record rests in SUBMITTED with the process id — nothing deployed.
        assertThat(membership.state()).isEqualTo(MembershipState.SUBMITTED);
        assertThat(membership.did()).isEqualTo(ACME_DID);
        assertThat(membership.onboardingProcessId()).isEqualTo("process-" + membership.externalId());
        assertThat(onboardingApi.callbackRegistrations).isEqualTo(1);
        assertThat(onboardingApi.submittedExternalIds).containsExactly(membership.externalId());
        assertThat(onboardingApi.submittedDids).containsExactly(ACME_DID);
        assertThat(tenantManager.deployed).isEmpty();
    }

    @Test
    void confirmedCallback_triggersProvisioning() {
        var membership = service.onboard(request(null));

        service.onRegistrationStatus(membership.externalId(), "CONFIRMED", null);

        var provisioned = stored(membership.externalId());
        assertThat(provisioned.state()).isEqualTo(MembershipState.PROVISIONING);
        assertThat(provisioned.tenantId()).isEqualTo("tenant-1");
        assertThat(provisioned.participantProfileId()).isEqualTo("profile-1");
        assertThat(provisioned.onboardingProcessId()).isEqualTo("process-" + membership.externalId());
        // Only ACTIVE consents make it into the cfm.issuer memberOf property, deployed under the
        // resolved DID.
        assertThat(tenantManager.deployedAgreements).containsExactly(List.of("agreement-1"));
        assertThat(tenantManager.deployed.get(0).did()).isEqualTo(ACME_DID);
    }

    @Test
    void aCallbackWithinTheSubmission_racesWithoutLosingEitherWrite() {
        // The current Onboarding API delivers the CONFIRMED callback while the submission is
        // still on the wire. Provisioning then runs BEFORE onboard records the process id — the
        // compare-and-swap must interleave the two writers so neither field is lost.
        onboardingApi.onSubmit = externalId -> service.onRegistrationStatus(externalId, "CONFIRMED", null);

        var membership = service.onboard(request(null));

        assertThat(membership.state()).isEqualTo(MembershipState.PROVISIONING);
        assertThat(membership.onboardingProcessId()).isEqualTo("process-" + membership.externalId());
        assertThat(membership.tenantId()).isEqualTo("tenant-1");
        assertThat(tenantManager.deployed).hasSize(1);
    }

    @Test
    void provisioning_completesImmediatelyWhenTheDeployResponseCarriesTheContextId() {
        tenantManager.contextIdOnDeploy = "pctx-1";
        var membership = service.onboard(request(null));

        service.onRegistrationStatus(membership.externalId(), "CONFIRMED", null);

        var provisioned = stored(membership.externalId());
        assertThat(provisioned.state()).isEqualTo(MembershipState.PROVISIONED);
        assertThat(provisioned.participantContextId()).isEqualTo("pctx-1");
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
        var failed = stored(repository.lastCreatedExternalId);
        assertThat(failed.state()).isEqualTo(MembershipState.FAILED);
        assertThat(failed.failureReason()).contains("Onboarding API unreachable");
        assertThat(tenantManager.deployed).isEmpty();
    }

    @Test
    void declinedCallback_terminallyRejectsWithoutProvisioning() {
        var membership = service.onboard(request(null));

        service.onRegistrationStatus(membership.externalId(), "DECLINED", "duplicate BPN");

        var rejected = stored(membership.externalId());
        assertThat(rejected.state()).isEqualTo(MembershipState.REJECTED);
        assertThat(rejected.failureReason()).isEqualTo("duplicate BPN");
        // The process id of the rejected onboarding is still recorded for audit.
        assertThat(rejected.onboardingProcessId()).isEqualTo("process-" + membership.externalId());
        assertThat(tenantManager.deployed).isEmpty();
    }

    @Test
    void duplicateConfirmed_doesNotProvisionTwice() {
        var membership = service.onboard(request(null));
        service.onRegistrationStatus(membership.externalId(), "CONFIRMED", null);

        service.onRegistrationStatus(membership.externalId(), "CONFIRMED", null);

        // The CONFIRMED->PROVISIONING claim is the at-most-once gate.
        assertThat(tenantManager.deployed).hasSize(1);
        assertThat(stored(membership.externalId()).state()).isEqualTo(MembershipState.PROVISIONING);
    }

    @Test
    void aRedeliveredConfirmed_healsAConfirmationWhoseWorkerNeverRan() {
        // Crash between recording CONFIRMED and the worker picking it up: the trigger is lost...
        provisioningExecutor = task -> { };
        var membership = service.onboard(request(null));
        service.onRegistrationStatus(membership.externalId(), "CONFIRMED", null);
        assertThat(stored(membership.externalId()).state()).isEqualTo(MembershipState.CONFIRMED);
        assertThat(tenantManager.deployed).isEmpty();

        // ...and the redelivered callback is the recovery path.
        provisioningExecutor = Runnable::run;
        service.onRegistrationStatus(membership.externalId(), "CONFIRMED", null);

        assertThat(stored(membership.externalId()).state()).isEqualTo(MembershipState.PROVISIONING);
        assertThat(tenantManager.deployed).hasSize(1);
    }

    @Test
    void provisioningFailure_landsOnTheRecord() {
        tenantManager.failDeployment = true;
        var membership = service.onboard(request(null));

        service.onRegistrationStatus(membership.externalId(), "CONFIRMED", null);

        var failed = stored(membership.externalId());
        assertThat(failed.state()).isEqualTo(MembershipState.FAILED);
        assertThat(failed.failureReason()).contains("Tenant Manager unreachable");
    }

    @Test
    void aLegacyRegisteringRecord_healsOnALateCallback() {
        // Rows the former synchronous flow left in REGISTERING must still advance when their
        // callback finally arrives.
        repository.create(Membership.submitted("legacy-1", "Acme Corp", ACME_DID, "BPNL0000000000XY"), request(null));
        repository.save(stored("legacy-1").withState(MembershipState.REGISTERING));

        service.onRegistrationStatus("legacy-1", "CONFIRMED", null);

        assertThat(stored("legacy-1").state()).isEqualTo(MembershipState.PROVISIONING);
        assertThat(tenantManager.deployed).hasSize(1);
    }

    @Test
    void get_readsTheProfileThroughTheStoredIdUntilTheContextIdAppears() {
        var membership = service.onboard(request(null));
        service.onRegistrationStatus(membership.externalId(), "CONFIRMED", null);
        assertThat(stored(membership.externalId()).state()).isEqualTo(MembershipState.PROVISIONING);

        // Context id not there yet: still PROVISIONING, read through the stored profile id.
        assertThat(service.get(membership.externalId()).state()).isEqualTo(MembershipState.PROVISIONING);
        assertThat(tenantManager.refreshCount).isEqualTo(1);

        // Once the platform assigns it, the next read completes the membership — persisted, not
        // just returned.
        tenantManager.contextIdOnRefresh = "pctx-9";
        var refreshed = service.get(membership.externalId());
        assertThat(refreshed.state()).isEqualTo(MembershipState.PROVISIONED);
        assertThat(refreshed.participantContextId()).isEqualTo("pctx-9");
        assertThat(stored(membership.externalId()).state()).isEqualTo(MembershipState.PROVISIONED);

        // A terminal membership is no longer read through the Tenant Manager.
        var countAfterCompletion = tenantManager.refreshCount;
        service.get(membership.externalId());
        assertThat(tenantManager.refreshCount).isEqualTo(countAfterCompletion);
    }

    @Test
    void get_failsAMembershipWhoseProfileReportsAnError() {
        var membership = service.onboard(request(null));
        service.onRegistrationStatus(membership.externalId(), "CONFIRMED", null);
        tenantManager.error = true;

        assertThat(service.get(membership.externalId()).state()).isEqualTo(MembershipState.FAILED);
    }

    @Test
    void get_returnsAMembershipWithoutAProfileAsStored() {
        var membership = service.onboard(request(null));

        assertThat(service.get(membership.externalId()).state()).isEqualTo(MembershipState.SUBMITTED);
        assertThat(tenantManager.refreshCount).isZero();
    }

    @Test
    void lateCallbacks_doNotDisturbATerminalMembership() {
        tenantManager.contextIdOnDeploy = "pctx-1";
        var membership = service.onboard(request(null));
        service.onRegistrationStatus(membership.externalId(), "CONFIRMED", null);
        assertThat(stored(membership.externalId()).state()).isEqualTo(MembershipState.PROVISIONED);

        // Redelivered or contradictory callbacks must not re-provision or overwrite the outcome.
        service.onRegistrationStatus(membership.externalId(), "CONFIRMED", null);
        service.onRegistrationStatus(membership.externalId(), "DECLINED", "too late");

        assertThat(stored(membership.externalId()).state()).isEqualTo(MembershipState.PROVISIONED);
        assertThat(tenantManager.deployed).hasSize(1);
    }

    @Test
    void anUnknownStatus_changesNothing() {
        var membership = service.onboard(request(null));

        service.onRegistrationStatus(membership.externalId(), "SUBMITTED", null);

        assertThat(stored(membership.externalId()).state()).isEqualTo(MembershipState.SUBMITTED);
        assertThat(tenantManager.deployed).isEmpty();
    }
}
