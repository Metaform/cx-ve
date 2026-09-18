package com.metaform.cxve.hub.application;

import com.metaform.cxve.hub.adapter.out.persistence.InMemoryMembershipRepository;
import com.metaform.cxve.hub.domain.model.MemberData;
import com.metaform.cxve.hub.domain.model.Membership;
import com.metaform.cxve.hub.domain.model.MembershipState;
import com.metaform.cxve.hub.domain.port.OnboardingApi;
import com.metaform.cxve.hub.domain.port.TenantManager;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * The onboarding choreography in the order the credential offer dictates: a member this
 * environment hosts is DEPLOYED first and REGISTERED second (its wallet has to exist before the
 * issuer pushes the offer the registration ends with), a member with its own DID is registered
 * straight away, and the confirmation callback is the terminal success of both. The worker
 * executor is swappable per test — direct execution for determinism, a deferring one to observe
 * the record while the deployment is still in flight.
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
            DID_TEMPLATE, PROVISIONING_TIMEOUT, Duration.ofMillis(1),
            task -> provisioningExecutor.execute(task));

    /** Holds the worker's task so a test can inspect the record before the deployment runs. */
    private final List<Runnable> deferredTasks = new ArrayList<>();

    private void runWorker() {
        var tasks = List.copyOf(deferredTasks);
        deferredTasks.clear();
        tasks.forEach(Runnable::run);
    }

    private static MemberData request(String did) {
        return request(did, "Acme", "BPNL0000000000XY");
    }

    private static MemberData request(String did, String shortName, String bpn) {
        return new MemberData("Acme Corp", shortName, bpn,
                "Berlin", "Musterstrasse", "DE", "BE", did,
                List.of(new MemberData.UniqueId("VAT_ID", "DE123456789")),
                List.of("ACTIVE_PARTICIPANT"),
                List.of(new MemberData.AgreementConsent("agreement-1", "ACTIVE"),
                        new MemberData.AgreementConsent("agreement-2", "INACTIVE")),
                List.of(new MemberData.UserDetail(null, "prov-1", "jdoe", "John", "Doe", "john.doe@acme.example")));
    }

    /** A member whose resources run elsewhere: it brings its own DID, so nothing is provisioned. */
    private static MemberData externalRequest() {
        return request(SUT_DID);
    }

    /** The DID the resolver derives for {@link #request}'s short name. */
    private static final String ACME_DID = DID_TEMPLATE + "Acme";

    /** The DID a member hosted elsewhere brings with it. */
    private static final String SUT_DID = "did:web:sut.example.com";

    /** Short enough that a test asserting the timeout does not have to wait for it. */
    private static final Duration PROVISIONING_TIMEOUT = Duration.ofMillis(200);

    private Membership stored(String externalId) {
        return repository.findByExternalId(externalId).orElseThrow();
    }

    @Test
    void onboard_aHostedMember_returnsWhileTheDeploymentIsStillRunning() {
        // The deployment takes minutes; the caller gets its record back immediately, in
        // PROVISIONING, and nothing has been registered yet.
        provisioningExecutor = deferredTasks::add;

        var membership = service.onboard(request(null));

        assertThat(membership.state()).isEqualTo(MembershipState.PROVISIONING);
        assertThat(membership.did()).isEqualTo(ACME_DID);
        assertThat(tenantManager.deployed).isEmpty();
        assertThat(onboardingApi.submittedExternalIds).isEmpty();

        // The worker then carries it the rest of the way on its own.
        tenantManager.contextIdOnDeploy = "pctx-1";
        runWorker();
        assertThat(tenantManager.deployed).hasSize(1);
        assertThat(onboardingApi.submittedExternalIds).containsExactly(membership.externalId());
    }

    @Test
    void aHostedMember_isDeployedBeforeItIsRegistered() {
        // The whole point of the order: the registration ends with the issuer pushing a credential
        // offer to the member's wallet, so the wallet has to exist by the time it is submitted.
        tenantManager.contextIdOnDeploy = "pctx-1";

        var membership = service.onboard(request(null));
        var externalId = membership.externalId();

        var submitted = stored(externalId);
        assertThat(submitted.state()).isEqualTo(MembershipState.SUBMITTED);
        assertThat(submitted.participantContextId()).isEqualTo("pctx-1");
        assertThat(submitted.tenantId()).isEqualTo("tenant-1");
        assertThat(submitted.participantProfileId()).isEqualTo("profile-1");
        assertThat(submitted.onboardingProcessId()).isEqualTo("process-" + externalId);
        assertThat(tenantManager.deployed).hasSize(1);
        assertThat(onboardingApi.callbackRegistrations).isEqualTo(1);
        assertThat(onboardingApi.submittedExternalIds).containsExactly(externalId);
        assertThat(onboardingApi.submittedDids).containsExactly(ACME_DID);
        // Only ACTIVE consents make it into the cfm.issuer memberOf property, deployed under the
        // resolved DID.
        assertThat(tenantManager.deployedAgreements).containsExactly(List.of("agreement-1"));
        assertThat(tenantManager.deployed.get(0).did()).isEqualTo(ACME_DID);
    }

    @Test
    void aHostedMember_isRegisteredOnlyOnceTheParticipantContextExists() {
        // The Tenant Manager reports the deployment's progress only when asked, so the worker
        // polls it and holds the registration back until the participant context appears.
        tenantManager.contextIdOnDeploy = null;
        tenantManager.contextIdOnRefresh = "pctx-late";

        var membership = service.onboard(request(null));

        assertThat(tenantManager.refreshCount).isPositive();
        assertThat(stored(membership.externalId()).participantContextId()).isEqualTo("pctx-late");
        assertThat(onboardingApi.submittedExternalIds).containsExactly(membership.externalId());
    }

    @Test
    void aDeploymentThatNeverCompletes_failsTheMembershipInsteadOfRegisteringIt() {
        // Registering a member whose wallet does not exist would have the credential offer fail at
        // the issuer and read as the member's fault; the unfinished deployment is the honest reason.
        tenantManager.contextIdOnDeploy = null;
        tenantManager.contextIdOnRefresh = null;

        var membership = service.onboard(request(null));

        var failed = stored(membership.externalId());
        assertThat(failed.state()).isEqualTo(MembershipState.FAILED);
        assertThat(failed.failureReason()).contains("Provisioning did not complete");
        assertThat(onboardingApi.submittedExternalIds).isEmpty();
    }

    @Test
    void aFailedDeployment_landsOnTheRecordAndStopsTheOnboarding() {
        tenantManager.failDeployment = true;

        var membership = service.onboard(request(null));

        var failed = stored(membership.externalId());
        assertThat(failed.state()).isEqualTo(MembershipState.FAILED);
        assertThat(failed.failureReason()).contains("Tenant Manager unreachable");
        assertThat(onboardingApi.submittedExternalIds).isEmpty();
    }

    @Test
    void aSubmissionThatFailsAfterDeployment_failsTheMembershipOnTheWorker() {
        // No caller is left to throw to — the record carries the reason. KNOWN GAP: the member's
        // EDC resources have been deployed by then and nothing takes them back.
        tenantManager.contextIdOnDeploy = "pctx-1";
        onboardingApi.failSubmission = true;

        var membership = service.onboard(request(null));

        var failed = stored(membership.externalId());
        assertThat(failed.state()).isEqualTo(MembershipState.FAILED);
        assertThat(failed.failureReason()).contains("Onboarding API unreachable");
        assertThat(tenantManager.deployed).hasSize(1);
    }

    @Test
    void aMemberThatBringsItsOwnDid_isRegisteredWithoutBeingProvisioned() {
        // Its connector and wallet already run elsewhere, so there is nothing to deploy — the
        // registration (and with it the credential offer) is all this environment does for it.
        var membership = service.onboard(externalRequest());

        assertThat(membership.state()).isEqualTo(MembershipState.SUBMITTED);
        assertThat(membership.onboardingProcessId()).isEqualTo("process-" + membership.externalId());
        assertThat(onboardingApi.submittedDids).containsExactly(SUT_DID);
        assertThat(tenantManager.deployed).isEmpty();
        assertThat(membership.tenantId()).isNull();
        assertThat(membership.participantProfileId()).isNull();
        assertThat(membership.participantContextId()).isNull();
    }

    @Test
    void confirmedCallback_isTheMembershipsTerminalSuccess() {
        // The Onboarding API confirms only once it has registered the credential holder AND had
        // the issuer offer it the credentials, so the confirmation IS "credentials offered".
        var membership = service.onboard(externalRequest());

        service.onRegistrationStatus(membership.externalId(), "CONFIRMED", null);

        var offered = stored(membership.externalId());
        assertThat(offered.state()).isEqualTo(MembershipState.CREDENTIALS_OFFERED);
        assertThat(offered.isTerminal()).isTrue();
    }

    @Test
    void aCallbackWithinTheSubmission_racesWithoutLosingEitherWrite() {
        // The current Onboarding API delivers the confirmation while the submission is still on
        // the wire, so the record reaches CREDENTIALS_OFFERED before the process id is recorded —
        // the compare-and-swap must interleave the two writers so neither field is lost.
        tenantManager.contextIdOnDeploy = "pctx-1";
        onboardingApi.onSubmit = externalId -> service.onRegistrationStatus(externalId, "CONFIRMED", null);

        var membership = service.onboard(request(null));

        var offered = stored(membership.externalId());
        assertThat(offered.state()).isEqualTo(MembershipState.CREDENTIALS_OFFERED);
        assertThat(offered.onboardingProcessId()).isEqualTo("process-" + membership.externalId());
        assertThat(offered.tenantId()).isEqualTo("tenant-1");
        assertThat(offered.participantContextId()).isEqualTo("pctx-1");
    }

    @Test
    void onboard_honorsACallerSuppliedDid() {
        var membership = service.onboard(request("did:web:acme.example.com"));

        assertThat(membership.did()).isEqualTo("did:web:acme.example.com");
        assertThat(onboardingApi.submittedDids).containsExactly("did:web:acme.example.com");
    }

    @Test
    void onboard_marksTheMembershipFailedWhenTheInlineSubmissionFails() {
        onboardingApi.failSubmission = true;

        var thrown = catchThrowable(() -> service.onboard(externalRequest()));

        assertThat(thrown).hasMessage("Onboarding API unreachable");
        // The record survives for audit, terminally failed — not wedged in SUBMITTED.
        var failed = stored(repository.lastCreatedExternalId);
        assertThat(failed.state()).isEqualTo(MembershipState.FAILED);
        assertThat(failed.failureReason()).contains("Onboarding API unreachable");
        assertThat(tenantManager.deployed).isEmpty();
    }

    @Test
    void onboard_refusesADidALiveMembershipAlreadyHolds() {
        service.onboard(externalRequest());

        var thrown = catchThrowable(() -> service.onboard(externalRequest()));

        assertThat(thrown).isInstanceOf(DuplicateMembershipException.class)
                .hasMessageContaining(SUT_DID);
        assertThat(onboardingApi.submittedExternalIds).hasSize(1);
    }

    @Test
    void onboard_refusesABpnALiveMembershipAlreadyHolds_beforeDeployingAnything() {
        // The check exists because the deployment comes FIRST now: a duplicate the Onboarding API
        // would decline must be caught while there is still nothing to leave behind.
        tenantManager.contextIdOnDeploy = "pctx-1";
        service.onboard(request(null));

        var thrown = catchThrowable(() -> service.onboard(request(null, "AcmeTwo", "BPNL0000000000XY")));

        assertThat(thrown).isInstanceOf(DuplicateMembershipException.class)
                .hasMessageContaining("BPNL0000000000XY");
        assertThat(tenantManager.deployed).hasSize(1);
    }

    @Test
    void onboard_allowsRetryingAfterADeadAttempt() {
        // A rejected or failed attempt releases its DID and BPN — otherwise a single bad
        // registration would retire the member's identity forever.
        onboardingApi.failSubmission = true;
        catchThrowable(() -> service.onboard(externalRequest()));
        onboardingApi.failSubmission = false;

        var retried = service.onboard(externalRequest());

        assertThat(retried.state()).isEqualTo(MembershipState.SUBMITTED);
        assertThat(repository.findByDid(SUT_DID)).hasSize(2);
    }

    @Test
    void declinedCallback_terminallyRejects() {
        var membership = service.onboard(externalRequest());

        service.onRegistrationStatus(membership.externalId(), "DECLINED", "duplicate BPN");

        var rejected = stored(membership.externalId());
        assertThat(rejected.state()).isEqualTo(MembershipState.REJECTED);
        assertThat(rejected.failureReason()).isEqualTo("duplicate BPN");
        // The process id of the rejected onboarding is still recorded for audit.
        assertThat(rejected.onboardingProcessId()).isEqualTo("process-" + membership.externalId());
    }

    @Test
    void aDeclinedRegistration_leavesAHostedMembersResourcesBehind() {
        // KNOWN GAP, asserted so it is not mistaken for a bug: the deployment precedes the
        // registration, and a decline does not undo it. The duplicate pre-check covers the case
        // this hub can see; nothing else disposes of such resources.
        tenantManager.contextIdOnDeploy = "pctx-1";
        var membership = service.onboard(request(null));

        service.onRegistrationStatus(membership.externalId(), "DECLINED", "BPN already registered");

        assertThat(stored(membership.externalId()).state()).isEqualTo(MembershipState.REJECTED);
        assertThat(stored(membership.externalId()).participantContextId()).isEqualTo("pctx-1");
        assertThat(tenantManager.deployed).hasSize(1);
    }

    @Test
    void lateCallbacks_doNotDisturbATerminalMembership() {
        tenantManager.contextIdOnDeploy = "pctx-1";
        var membership = service.onboard(request(null));
        service.onRegistrationStatus(membership.externalId(), "CONFIRMED", null);
        assertThat(stored(membership.externalId()).state()).isEqualTo(MembershipState.CREDENTIALS_OFFERED);

        // Redelivered or contradictory callbacks must not re-deploy or overwrite the outcome.
        service.onRegistrationStatus(membership.externalId(), "CONFIRMED", null);
        service.onRegistrationStatus(membership.externalId(), "DECLINED", "too late");

        assertThat(stored(membership.externalId()).state()).isEqualTo(MembershipState.CREDENTIALS_OFFERED);
        assertThat(tenantManager.deployed).hasSize(1);
    }

    @Test
    void aLegacyRegisteringRecord_healsOnALateCallback() {
        // Rows the former synchronous flow left in REGISTERING must still advance when their
        // callback finally arrives.
        repository.create(Membership.submitted("legacy-1", "Acme Corp", ACME_DID, "BPNL0000000000XY"), request(null));
        repository.save(stored("legacy-1").withState(MembershipState.REGISTERING));

        service.onRegistrationStatus("legacy-1", "CONFIRMED", null);

        assertThat(stored("legacy-1").state()).isEqualTo(MembershipState.CREDENTIALS_OFFERED);
    }

    @Test
    void aLegacyConfirmedRecord_healsOnARedeliveredCallback() {
        // Rows an older hub left in CONFIRMED (it offered the credentials itself, after the
        // confirmation) reach the terminal state on the next callback rather than stranding.
        repository.create(Membership.submitted("legacy-2", "Acme Corp", ACME_DID, "BPNL0000000000XY"), request(null));
        repository.save(stored("legacy-2").withState(MembershipState.CONFIRMED));

        service.onRegistrationStatus("legacy-2", "CONFIRMED", null);

        assertThat(stored("legacy-2").state()).isEqualTo(MembershipState.CREDENTIALS_OFFERED);
    }

    @Test
    void get_readsTheProfileThroughTheStoredIdUntilTheContextIdAppears() {
        // A record left PROVISIONING by a worker that died mid-deployment: a read still completes
        // it from the Tenant Manager, which is what the operator's polling relies on.
        repository.create(Membership.provisioning("stranded-1", "Acme Corp", ACME_DID, "BPNL0000000000XY"),
                request(null));
        repository.save(stored("stranded-1").withProfile("tenant-1", "profile-1"));

        // Context id not there yet: still PROVISIONING, read through the stored profile id.
        assertThat(service.get("stranded-1").state()).isEqualTo(MembershipState.PROVISIONING);
        assertThat(tenantManager.refreshCount).isEqualTo(1);

        // Once the platform assigns it, the next read advances the membership — persisted, not
        // just returned.
        tenantManager.contextIdOnRefresh = "pctx-9";
        var refreshed = service.get("stranded-1");
        assertThat(refreshed.state()).isEqualTo(MembershipState.PROVISIONED);
        assertThat(refreshed.participantContextId()).isEqualTo("pctx-9");
        assertThat(stored("stranded-1").state()).isEqualTo(MembershipState.PROVISIONED);

        // A terminal membership is no longer read through the Tenant Manager.
        repository.save(stored("stranded-1").credentialsOffered());
        var countAfterCompletion = tenantManager.refreshCount;
        service.get("stranded-1");
        assertThat(tenantManager.refreshCount).isEqualTo(countAfterCompletion);
    }

    @Test
    void get_failsAMembershipWhoseProfileReportsAnError() {
        repository.create(Membership.provisioning("erroring-1", "Acme Corp", ACME_DID, "BPNL0000000000XY"),
                request(null));
        repository.save(stored("erroring-1").withProfile("tenant-1", "profile-1"));
        tenantManager.error = true;

        assertThat(service.get("erroring-1").state()).isEqualTo(MembershipState.FAILED);
    }

    @Test
    void get_returnsAMembershipWithoutAProfileAsStored() {
        var membership = service.onboard(externalRequest());

        assertThat(service.get(membership.externalId()).state()).isEqualTo(MembershipState.SUBMITTED);
        assertThat(tenantManager.refreshCount).isZero();
    }

    @Test
    void anUnknownStatus_changesNothing() {
        var membership = service.onboard(externalRequest());

        service.onRegistrationStatus(membership.externalId(), "SUBMITTED", null);

        assertThat(stored(membership.externalId()).state()).isEqualTo(MembershipState.SUBMITTED);
    }
}
