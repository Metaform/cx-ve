package com.metaform.cxve.hub.application;

import com.metaform.cxve.hub.adapter.out.persistence.InMemoryMembershipRepository;
import com.metaform.cxve.hub.config.DataspaceProperties;
import com.metaform.cxve.hub.domain.model.MemberData;
import com.metaform.cxve.hub.domain.model.Membership;
import com.metaform.cxve.hub.domain.model.MembershipState;
import com.metaform.cxve.hub.domain.model.RegistrationOutcome;
import com.metaform.cxve.hub.domain.port.DataspaceOnboarding;
import com.metaform.cxve.hub.domain.port.TenantManager;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
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
    private static final String DATASPACE = "test-space";
    private static final String OTHER_DATASPACE = "other-space";

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
     * A dataspace's onboarding that records calls; can be told to fail or to refuse the
     * registration object, and runs a hook while the "HTTP call" is in flight — where the CX
     * onboarding API's synchronous status callback lands in production. Its callback wire format
     * is a plain {externalId, status, message}.
     */
    private static class RecordingOnboarding implements DataspaceOnboarding {
        final String dataspace;
        final List<String> submittedExternalIds = new ArrayList<>();
        final List<String> submittedDids = new ArrayList<>();
        int callbackRegistrations;
        boolean failSubmission;
        boolean refuseRegistration;
        Consumer<String> onSubmit = externalId -> { };

        RecordingOnboarding(String dataspace) {
            this.dataspace = dataspace;
        }

        @Override
        public String dataspace() {
            return dataspace;
        }

        @Override
        public void validate(MemberData data) {
            if (refuseRegistration) {
                throw new InvalidRegistrationException("registration.city must not be blank");
            }
        }

        @Override
        public Map<String, Object> issuerProperties(String did, MemberData data) {
            return Map.of("id", did, "memberOf", data.registration().get("memberOf"), "memberId", data.memberId());
        }

        @Override
        public RegistrationOutcome readCallback(Map<String, Object> body) {
            return new RegistrationOutcome((String) body.get("externalId"),
                    RegistrationOutcome.Status.valueOf((String) body.get("status")), (String) body.get("message"));
        }

        @Override
        public void registerCallback() {
            callbackRegistrations++;
        }

        @Override
        public String submitRegistration(String externalId, String did, MemberData data) {
            if (failSubmission) {
                throw new RuntimeException("onboarding API unreachable");
            }
            onSubmit.accept(externalId);
            submittedExternalIds.add(externalId);
            submittedDids.add(did);
            return "process-" + externalId;
        }
    }

    /** Deploys with a configurable context id; records what it was handed and how often refreshed. */
    private static class RecordingTenantManager implements TenantManager {
        final List<DeploymentSpec> deployedSpecs = new ArrayList<>();
        final List<Membership> deployed = new ArrayList<>();
        String contextIdOnDeploy;
        String contextIdOnRefresh;
        int refreshCount;
        boolean error;
        boolean failDeployment;

        @Override
        public ProvisionedProfile deployParticipant(Membership membership, DeploymentSpec spec) {
            if (failDeployment) {
                throw new RuntimeException("Tenant Manager unreachable");
            }
            deployed.add(membership);
            deployedSpecs.add(spec);
            return new ProvisionedProfile("tenant-1", "profile-1", contextIdOnDeploy, error);
        }

        @Override
        public ProvisionedProfile refresh(Membership membership) {
            refreshCount++;
            return new ProvisionedProfile(membership.tenantId(), membership.participantProfileId(),
                    contextIdOnRefresh, error);
        }
    }

    private final RecordingOnboarding onboardingApi = new RecordingOnboarding(DATASPACE);
    private final RecordingOnboarding otherOnboarding = new RecordingOnboarding(OTHER_DATASPACE);
    private final DataspaceRegistry dataspaces = new DataspaceRegistry(List.of(onboardingApi, otherOnboarding),
            new DataspaceProperties(Map.of(
                    DATASPACE, dataspace("profile-a", new DataspaceProperties.MemberIdClaim("IdCredential", "id", "memberId")),
                    OTHER_DATASPACE, dataspace("profile-b", null))));
    private final RecordingTenantManager tenantManager = new RecordingTenantManager();
    // swappable per test; the service holds the indirection, not the executor itself
    private Executor provisioningExecutor = Runnable::run;
    private final MembershipService service = new MembershipService(repository, dataspaces, tenantManager,
            DID_TEMPLATE, PROVISIONING_TIMEOUT, Duration.ofMillis(1),
            task -> provisioningExecutor.execute(task));

    /** Holds the worker's task so a test can inspect the record before the deployment runs. */
    private final List<Runnable> deferredTasks = new ArrayList<>();

    private void runWorker() {
        var tasks = List.copyOf(deferredTasks);
        deferredTasks.clear();
        tasks.forEach(Runnable::run);
    }

    private static DataspaceProperties.Dataspace dataspace(String profile, DataspaceProperties.MemberIdClaim claim) {
        return new DataspaceProperties.Dataspace(true, profile, null, List.of(profile), claim);
    }

    private static MemberData request(String did) {
        return request(did, "Acme", "MEMBER-0001");
    }

    private static MemberData request(String did, String shortName, String memberId) {
        return request(DATASPACE, did, shortName, memberId);
    }

    private static MemberData request(String dataspace, String did, String shortName, String memberId) {
        return new MemberData(dataspace, "Acme Corp", shortName, memberId, did, Map.of("memberOf", "Test Space"));
    }

    /** A status callback in the recording onboarding's wire format, for the test dataspace. */
    private Membership callback(String externalId, String status, String message) {
        var body = new HashMap<String, Object>();
        body.put("externalId", externalId);
        body.put("status", status);
        body.put("message", message);
        return service.onRegistrationStatus(DATASPACE, body);
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
        // The dataspace decides the profile's dataspace-dependent parts: its onboarding the
        // cfm.issuer properties, its settings the DSP profiles and the member-id claim.
        var spec = tenantManager.deployedSpecs.get(0);
        assertThat(spec.issuerProperties())
                .containsEntry("id", ACME_DID)
                .containsEntry("memberOf", "Test Space")
                .containsEntry("memberId", "MEMBER-0001");
        assertThat(spec.dataspaceProfiles()).containsExactly("profile-a");
        assertThat(spec.memberIdClaim()).isEqualTo(new TenantManager.MemberIdClaim("IdCredential", "id", "memberId"));
        assertThat(tenantManager.deployed.get(0).did()).isEqualTo(ACME_DID);
        assertThat(tenantManager.deployed.get(0).dataspace()).isEqualTo(DATASPACE);
        assertThat(otherOnboarding.submittedExternalIds).isEmpty();
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
        assertThat(failed.failureReason()).contains("onboarding API unreachable");
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
        // The onboarding API confirms only once it has registered the credential holder AND had
        // the issuer offer it the credentials, so the confirmation IS "credentials offered".
        var membership = service.onboard(externalRequest());

        callback(membership.externalId(), "CONFIRMED", null);

        var offered = stored(membership.externalId());
        assertThat(offered.state()).isEqualTo(MembershipState.CREDENTIALS_OFFERED);
        assertThat(offered.isTerminal()).isTrue();
    }

    @Test
    void aCallbackWithinTheSubmission_racesWithoutLosingEitherWrite() {
        // The CX onboarding API delivers the confirmation while the submission is still on
        // the wire, so the record reaches CREDENTIALS_OFFERED before the process id is recorded —
        // the compare-and-swap must interleave the two writers so neither field is lost.
        tenantManager.contextIdOnDeploy = "pctx-1";
        onboardingApi.onSubmit = externalId -> callback(externalId, "CONFIRMED", null);

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

        assertThat(thrown).hasMessage("onboarding API unreachable");
        // The record survives for audit, terminally failed — not wedged in SUBMITTED.
        var failed = stored(repository.lastCreatedExternalId);
        assertThat(failed.state()).isEqualTo(MembershipState.FAILED);
        assertThat(failed.failureReason()).contains("onboarding API unreachable");
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
    void onboard_refusesAMemberIdALiveMembershipAlreadyHolds_beforeDeployingAnything() {
        // The check exists because the deployment comes FIRST: a duplicate the onboarding API
        // would decline must be caught while there is still nothing to leave behind.
        tenantManager.contextIdOnDeploy = "pctx-1";
        service.onboard(request(null));

        var thrown = catchThrowable(() -> service.onboard(request(null, "AcmeTwo", "MEMBER-0001")));

        assertThat(thrown).isInstanceOf(DuplicateMembershipException.class)
                .hasMessageContaining("MEMBER-0001");
        assertThat(tenantManager.deployed).hasSize(1);
    }

    @Test
    void onboard_allowsTheSameMemberIdAndExternalDidInAnotherDataspace() {
        // Member ids are only unique within their dataspace, and a member running elsewhere may
        // join several dataspaces under one DID.
        service.onboard(externalRequest());

        var other = service.onboard(request(OTHER_DATASPACE, SUT_DID, "Acme", "MEMBER-0001"));

        assertThat(other.dataspace()).isEqualTo(OTHER_DATASPACE);
        assertThat(otherOnboarding.submittedDids).containsExactly(SUT_DID);
        assertThat(onboardingApi.submittedDids).containsExactly(SUT_DID);
    }

    @Test
    void onboard_refusesAHostedDidInASecondDataspace() {
        // A hosted member's DID IS a deployed participant profile — a second membership would
        // deploy the same identity twice.
        tenantManager.contextIdOnDeploy = "pctx-1";
        service.onboard(request(null));

        var thrown = catchThrowable(() -> service.onboard(request(OTHER_DATASPACE, null, "Acme", "OTHER-1")));

        assertThat(thrown).isInstanceOf(DuplicateMembershipException.class).hasMessageContaining(ACME_DID);
        assertThat(tenantManager.deployed).hasSize(1);
    }

    @Test
    void onboard_refusesAnUnservedDataspace_beforeCreatingAnything() {
        var thrown = catchThrowable(() -> service.onboard(request("decade-x", null, "Acme", "DX-1")));

        assertThat(thrown).isInstanceOf(DataspaceOnboarding.UnknownDataspaceException.class)
                .hasMessageContaining("decade-x");
        assertThat(repository.lastCreatedExternalId).isNull();
    }

    @Test
    void onboard_refusesARegistrationObjectTheDataspaceRejects_beforeCreatingAnything() {
        onboardingApi.refuseRegistration = true;

        var thrown = catchThrowable(() -> service.onboard(request(null)));

        assertThat(thrown).isInstanceOf(DataspaceOnboarding.InvalidRegistrationException.class);
        assertThat(repository.lastCreatedExternalId).isNull();
        assertThat(tenantManager.deployed).isEmpty();
    }

    @Test
    void aCallbackFromAnotherDataspacesApi_isNotForThisMembership() {
        var membership = service.onboard(externalRequest());
        var body = new HashMap<String, Object>(Map.of("externalId", membership.externalId(), "status", "CONFIRMED"));

        var thrown = catchThrowable(() -> service.onRegistrationStatus(OTHER_DATASPACE, body));

        assertThat(thrown).isInstanceOf(NoSuchElementException.class);
        assertThat(stored(membership.externalId()).state()).isEqualTo(MembershipState.SUBMITTED);
    }

    @Test
    void onboard_allowsRetryingAfterADeadAttempt() {
        // A rejected or failed attempt releases its DID and member id — otherwise a single bad
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

        callback(membership.externalId(), "DECLINED", "duplicate member id");

        var rejected = stored(membership.externalId());
        assertThat(rejected.state()).isEqualTo(MembershipState.REJECTED);
        assertThat(rejected.failureReason()).isEqualTo("duplicate member id");
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

        callback(membership.externalId(), "DECLINED", "member id already registered");

        assertThat(stored(membership.externalId()).state()).isEqualTo(MembershipState.REJECTED);
        assertThat(stored(membership.externalId()).participantContextId()).isEqualTo("pctx-1");
        assertThat(tenantManager.deployed).hasSize(1);
    }

    @Test
    void lateCallbacks_doNotDisturbATerminalMembership() {
        tenantManager.contextIdOnDeploy = "pctx-1";
        var membership = service.onboard(request(null));
        callback(membership.externalId(), "CONFIRMED", null);
        assertThat(stored(membership.externalId()).state()).isEqualTo(MembershipState.CREDENTIALS_OFFERED);

        // Redelivered or contradictory callbacks must not re-deploy or overwrite the outcome.
        callback(membership.externalId(), "CONFIRMED", null);
        callback(membership.externalId(), "DECLINED", "too late");

        assertThat(stored(membership.externalId()).state()).isEqualTo(MembershipState.CREDENTIALS_OFFERED);
        assertThat(tenantManager.deployed).hasSize(1);
    }

    @Test
    void aLegacyRegisteringRecord_healsOnALateCallback() {
        // Rows the former synchronous flow left in REGISTERING must still advance when their
        // callback finally arrives.
        repository.create(Membership.submitted("legacy-1", DATASPACE, "Acme Corp", ACME_DID, "MEMBER-0001"), request(null));
        repository.save(stored("legacy-1").withState(MembershipState.REGISTERING));

        callback("legacy-1", "CONFIRMED", null);

        assertThat(stored("legacy-1").state()).isEqualTo(MembershipState.CREDENTIALS_OFFERED);
    }

    @Test
    void aLegacyConfirmedRecord_healsOnARedeliveredCallback() {
        // Rows an older hub left in CONFIRMED (it offered the credentials itself, after the
        // confirmation) reach the terminal state on the next callback rather than stranding.
        repository.create(Membership.submitted("legacy-2", DATASPACE, "Acme Corp", ACME_DID, "MEMBER-0001"), request(null));
        repository.save(stored("legacy-2").withState(MembershipState.CONFIRMED));

        callback("legacy-2", "CONFIRMED", null);

        assertThat(stored("legacy-2").state()).isEqualTo(MembershipState.CREDENTIALS_OFFERED);
    }

    @Test
    void get_readsTheProfileThroughTheStoredIdUntilTheContextIdAppears() {
        // A record left PROVISIONING by a worker that died mid-deployment: a read still completes
        // it from the Tenant Manager, which is what the operator's polling relies on.
        repository.create(Membership.provisioning("stranded-1", DATASPACE, "Acme Corp", ACME_DID, "MEMBER-0001"),
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
        repository.create(Membership.provisioning("erroring-1", DATASPACE, "Acme Corp", ACME_DID, "MEMBER-0001"),
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

        callback(membership.externalId(), "PENDING", null);

        assertThat(stored(membership.externalId()).state()).isEqualTo(MembershipState.SUBMITTED);
    }
}
