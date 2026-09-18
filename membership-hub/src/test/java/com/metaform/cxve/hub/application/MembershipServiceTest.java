package com.metaform.cxve.hub.application;

import com.metaform.cxve.hub.adapter.out.persistence.InMemoryMembershipRepository;
import com.metaform.cxve.hub.domain.model.MemberData;
import com.metaform.cxve.hub.domain.model.Membership;
import com.metaform.cxve.hub.domain.model.MembershipState;
import com.metaform.cxve.hub.domain.port.CredentialOfferService;
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
 * The asynchronous choreography: {@code onboard} only submits, the status callback drives the
 * outcome, CONFIRMED triggers the post-confirmation work on the worker — deploying the member's
 * resources, or offering credentials to an externally hosted one. The worker executor is
 * swappable per test — direct execution for determinism, a dropping executor to simulate a crash
 * between confirmation and claim.
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

    /** Records the offers it was asked to send; can be told to fail. */
    private static class RecordingCredentialOffers implements CredentialOfferService {
        final List<Membership> offered = new ArrayList<>();
        boolean failOffer;

        @Override
        public void sendOffer(Membership membership) {
            if (failOffer) {
                throw new RuntimeException("could not resolve the holder's credential service");
            }
            offered.add(membership);
        }
    }

    private final RecordingOnboardingApi onboardingApi = new RecordingOnboardingApi();
    private final RecordingTenantManager tenantManager = new RecordingTenantManager();
    private final RecordingCredentialOffers credentialOffers = new RecordingCredentialOffers();
    // swappable per test; the service holds the indirection, not the executor itself
    private Executor provisioningExecutor = Runnable::run;
    private final MembershipService service = new MembershipService(repository, onboardingApi, tenantManager,
            credentialOffers, DID_TEMPLATE, PROVISIONING_TIMEOUT, Duration.ofMillis(1),
            task -> provisioningExecutor.execute(task));

    private static MemberData request(String did) {
        return new MemberData("Acme Corp", "Acme", "BPNL0000000000XY",
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
        tenantManager.contextIdOnDeploy = "pctx-1";
        var membership = service.onboard(request(null));

        service.onRegistrationStatus(membership.externalId(), "CONFIRMED", null);

        var provisioned = stored(membership.externalId());
        assertThat(provisioned.state()).isEqualTo(MembershipState.CREDENTIALS_OFFERED);
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
        tenantManager.contextIdOnDeploy = "pctx-1";
        onboardingApi.onSubmit = externalId -> service.onRegistrationStatus(externalId, "CONFIRMED", null);

        var membership = service.onboard(request(null));

        assertThat(membership.onboardingProcessId()).isEqualTo("process-" + membership.externalId());
        assertThat(membership.tenantId()).isEqualTo("tenant-1");
        assertThat(stored(membership.externalId()).state()).isEqualTo(MembershipState.CREDENTIALS_OFFERED);
        assertThat(stored(membership.externalId()).onboardingProcessId())
                .isEqualTo("process-" + membership.externalId());
        assertThat(tenantManager.deployed).hasSize(1);
    }

    @Test
    void aHostedMember_isDeployedAndThenOfferedItsCredentials() {
        // The point of the unified pipeline: a member provisioned here receives its credentials
        // exactly as a third-party one does — the hub asks the issuer to offer them, and the
        // member's own wallet requests them. No CFM activity does that any more.
        tenantManager.contextIdOnDeploy = "pctx-1";
        var membership = service.onboard(request(null));

        service.onRegistrationStatus(membership.externalId(), "CONFIRMED", null);

        var offered = stored(membership.externalId());
        assertThat(tenantManager.deployed).hasSize(1);
        assertThat(offered.participantContextId()).isEqualTo("pctx-1");
        assertThat(offered.state()).isEqualTo(MembershipState.CREDENTIALS_OFFERED);
        assertThat(offered.isTerminal()).isTrue();
        assertThat(credentialOffers.offered).hasSize(1);
        assertThat(credentialOffers.offered.get(0).did()).isEqualTo(ACME_DID);
    }

    @Test
    void aHostedMember_isOfferedCredentialsOnlyOnceTheParticipantContextExists() {
        // The offer needs the member's wallet and DID document to be there; the Tenant Manager
        // reports the deployment's progress only when asked, so the worker waits for it.
        tenantManager.contextIdOnDeploy = null;
        tenantManager.contextIdOnRefresh = "pctx-late";
        var membership = service.onboard(request(null));

        service.onRegistrationStatus(membership.externalId(), "CONFIRMED", null);

        assertThat(tenantManager.refreshCount).isPositive();
        assertThat(credentialOffers.offered).hasSize(1);
        assertThat(credentialOffers.offered.get(0).participantContextId()).isEqualTo("pctx-late");
        assertThat(stored(membership.externalId()).state()).isEqualTo(MembershipState.CREDENTIALS_OFFERED);
    }

    @Test
    void aDeploymentThatNeverCompletes_failsTheMembershipInsteadOfOfferingCredentials() {
        // Offering credentials to a wallet that does not exist yet would fail at the issuer and
        // read as the member's fault; the unfinished deployment is the honest reason.
        tenantManager.contextIdOnDeploy = null;
        tenantManager.contextIdOnRefresh = null;
        var membership = service.onboard(request(null));

        service.onRegistrationStatus(membership.externalId(), "CONFIRMED", null);

        var failed = stored(membership.externalId());
        assertThat(failed.state()).isEqualTo(MembershipState.FAILED);
        assertThat(failed.failureReason()).contains("Provisioning did not complete");
        assertThat(credentialOffers.offered).isEmpty();
    }

    @Test
    void aMemberThatBringsItsOwnDid_isOfferedCredentialsWithoutBeingProvisioned() {
        // Its connector and wallet already run elsewhere; the only thing this environment can do
        // for it is have the issuer offer the credentials to that wallet.
        var membership = service.onboard(externalRequest());

        service.onRegistrationStatus(membership.externalId(), "CONFIRMED", null);

        var offered = stored(membership.externalId());
        assertThat(offered.state()).isEqualTo(MembershipState.CREDENTIALS_OFFERED);
        assertThat(offered.isTerminal()).isTrue();
        assertThat(credentialOffers.offered).hasSize(1);
        assertThat(credentialOffers.offered.get(0).did()).isEqualTo(SUT_DID);
        // Nothing was deployed, so none of the provisioning identifiers exist — the member's own
        // DID is what says so, rather than the membership being unfinished.
        assertThat(tenantManager.deployed).isEmpty();
        assertThat(offered.tenantId()).isNull();
        assertThat(offered.participantProfileId()).isNull();
        assertThat(offered.participantContextId()).isNull();
    }

    @Test
    void aMemberIsOfferedCredentialsAtMostOnce() {
        var membership = service.onboard(externalRequest());
        service.onRegistrationStatus(membership.externalId(), "CONFIRMED", null);

        // Redelivered and contradictory callbacks alike leave the terminal outcome alone.
        service.onRegistrationStatus(membership.externalId(), "CONFIRMED", null);
        service.onRegistrationStatus(membership.externalId(), "DECLINED", "too late");

        assertThat(credentialOffers.offered).hasSize(1);
        assertThat(stored(membership.externalId()).state()).isEqualTo(MembershipState.CREDENTIALS_OFFERED);
    }

    @Test
    void aFailedCredentialOffer_landsOnTheRecord() {
        // Typically the member's DID document or Credential Service being unreachable from here —
        // a failure of the membership, not something to hide from the operator.
        credentialOffers.failOffer = true;
        var membership = service.onboard(externalRequest());

        service.onRegistrationStatus(membership.externalId(), "CONFIRMED", null);

        var failed = stored(membership.externalId());
        assertThat(failed.state()).isEqualTo(MembershipState.FAILED);
        assertThat(failed.failureReason()).contains("could not resolve the holder's credential service");
        assertThat(tenantManager.deployed).isEmpty();
    }

    @Test
    void aMemberThatBringsItsOwnDid_isNeverReadThroughTheTenantManager() {
        var membership = service.onboard(externalRequest());
        service.onRegistrationStatus(membership.externalId(), "CONFIRMED", null);

        assertThat(service.get(membership.externalId()).state()).isEqualTo(MembershipState.CREDENTIALS_OFFERED);
        assertThat(tenantManager.refreshCount).isZero();
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
        tenantManager.contextIdOnDeploy = "pctx-1";
        var membership = service.onboard(request(null));
        service.onRegistrationStatus(membership.externalId(), "CONFIRMED", null);

        service.onRegistrationStatus(membership.externalId(), "CONFIRMED", null);

        // The CONFIRMED->PROVISIONING claim is the at-most-once gate.
        assertThat(tenantManager.deployed).hasSize(1);
        assertThat(stored(membership.externalId()).state()).isEqualTo(MembershipState.CREDENTIALS_OFFERED);
    }

    @Test
    void aRedeliveredConfirmed_healsAConfirmationWhoseWorkerNeverRan() {
        // Crash between recording CONFIRMED and the worker picking it up: the trigger is lost...
        tenantManager.contextIdOnDeploy = "pctx-1";
        provisioningExecutor = task -> { };
        var membership = service.onboard(request(null));
        service.onRegistrationStatus(membership.externalId(), "CONFIRMED", null);
        assertThat(stored(membership.externalId()).state()).isEqualTo(MembershipState.CONFIRMED);
        assertThat(tenantManager.deployed).isEmpty();

        // ...and the redelivered callback is the recovery path.
        provisioningExecutor = Runnable::run;
        service.onRegistrationStatus(membership.externalId(), "CONFIRMED", null);

        assertThat(stored(membership.externalId()).state()).isEqualTo(MembershipState.CREDENTIALS_OFFERED);
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
        tenantManager.contextIdOnDeploy = "pctx-1";
        repository.create(Membership.submitted("legacy-1", "Acme Corp", ACME_DID, "BPNL0000000000XY"), request(null));
        repository.save(stored("legacy-1").withState(MembershipState.REGISTERING));

        service.onRegistrationStatus("legacy-1", "CONFIRMED", null);

        assertThat(stored("legacy-1").state()).isEqualTo(MembershipState.CREDENTIALS_OFFERED);
        assertThat(tenantManager.deployed).hasSize(1);
    }

    @Test
    void get_readsTheProfileThroughTheStoredIdUntilTheContextIdAppears() {
        // A record left PROVISIONING by a worker that died mid-deployment: a read still completes
        // it from the Tenant Manager, which is what the operator's polling relies on.
        repository.create(Membership.submitted("stranded-1", "Acme Corp", ACME_DID, "BPNL0000000000XY"),
                request(null));
        repository.save(stored("stranded-1").provisioning("tenant-1", "profile-1"));

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
        repository.create(Membership.submitted("erroring-1", "Acme Corp", ACME_DID, "BPNL0000000000XY"),
                request(null));
        repository.save(stored("erroring-1").provisioning("tenant-1", "profile-1"));
        tenantManager.error = true;

        assertThat(service.get("erroring-1").state()).isEqualTo(MembershipState.FAILED);
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
        assertThat(stored(membership.externalId()).state()).isEqualTo(MembershipState.CREDENTIALS_OFFERED);

        // Redelivered or contradictory callbacks must not re-provision or overwrite the outcome.
        service.onRegistrationStatus(membership.externalId(), "CONFIRMED", null);
        service.onRegistrationStatus(membership.externalId(), "DECLINED", "too late");

        assertThat(stored(membership.externalId()).state()).isEqualTo(MembershipState.CREDENTIALS_OFFERED);
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
