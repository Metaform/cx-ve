package com.metaform.cxve.verification.domain.model;

import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;

/**
 * One verification run of one use case in one dataspace: the participant-under-test's identity plus
 * the live step ledger the
 * executing flow writes and the UI polls. Mutated only by the run's executor thread, read by web
 * threads — every access goes through synchronized methods, and readers only ever see immutable
 * {@link Snapshot}s. State is in-memory by design (v1): a pod restart loses the run record, but
 * nothing durable — the participant lives in the hub's database and the events in the tracker's.
 *
 * <p>The ledger holds exactly the steps the run's own sequence declares (the use case's, for a
 * managed or an external participant), so the timeline never shows a step this run was never going
 * to take.
 */
public class VerificationRun {

    private final String id;
    private final Instant startedAt = Instant.now();
    private final String dataspace;
    private final String useCase;
    private final String name;
    private final String shortName;
    private final String memberId;
    private final String uniqueId;
    /** The declared DID of an externally hosted participant; null for one this environment hosts. */
    private final String declaredDid;
    private final EnumMap<RunStep, StepRecord> steps = new EnumMap<>(RunStep.class);

    private RunState state = RunState.RUNNING;
    private Instant finishedAt;
    private String failureReason;
    private RunStep failedStep;

    // learned as the run progresses
    private String externalId;
    private String did;
    private String participantContextId;
    private String onboardingProcessId;
    private VerificationParticipant verificationParticipant;
    private List<ChecklistItem> checklist = List.of();

    public VerificationRun(String id, String dataspace, String useCase, String name, String shortName,
                           String memberId, String uniqueId, String declaredDid, List<RunStep> sequence) {
        this.id = id;
        this.dataspace = dataspace;
        this.useCase = useCase;
        this.name = name;
        this.shortName = shortName;
        this.memberId = memberId;
        this.uniqueId = uniqueId;
        this.declaredDid = declaredDid;
        this.did = declaredDid;
        for (var step : sequence) {
            steps.put(step, new StepRecord());
        }
    }

    public String id() {
        return id;
    }

    public String dataspace() {
        return dataspace;
    }

    public String useCase() {
        return useCase;
    }

    public String name() {
        return name;
    }

    public String shortName() {
        return shortName;
    }

    /** The participant's id within the run's dataspace — the BPN in Catena-X, the DECADE-X-ID in DECADE-X. */
    public String memberId() {
        return memberId;
    }

    /** A unique identifier for the dataspace's registration (e.g. a VAT id), derived per participant. */
    public String uniqueId() {
        return uniqueId;
    }

    /** Non-null exactly when this run verifies a participant hosted outside this environment. */
    public String declaredDid() {
        return declaredDid;
    }

    public boolean externallyHosted() {
        return declaredDid != null;
    }

    public synchronized String did() {
        return did;
    }

    public synchronized String externalId() {
        return externalId;
    }

    public synchronized String onboardingProcessId() {
        return onboardingProcessId;
    }

    public synchronized void stepStarted(RunStep step) {
        var record = steps.get(step);
        record.status = StepStatus.RUNNING;
        record.startedAt = Instant.now();
    }

    public synchronized void stepOk(RunStep step, String detail) {
        var record = steps.get(step);
        record.status = StepStatus.OK;
        record.detail = detail;
        record.finishedAt = Instant.now();
    }

    /** Marks the step failed and ends the run: later steps become SKIPPED, the run FAILED. */
    public synchronized void stepFailed(RunStep step, String reason) {
        var record = steps.get(step);
        record.status = StepStatus.FAILED;
        record.detail = reason;
        record.finishedAt = Instant.now();
        failedStep = step;
        fail(reason);
    }

    /** Terminal failure — a no-op when the run already ended (the step ledger stays as-is). */
    public synchronized void fail(String reason) {
        if (state != RunState.RUNNING) {
            return;
        }
        state = RunState.FAILED;
        failureReason = reason;
        finishedAt = Instant.now();
        steps.values().stream()
                .filter(record -> record.status == StepStatus.PENDING)
                .forEach(record -> record.status = StepStatus.SKIPPED);
    }

    public synchronized void succeed() {
        if (state != RunState.RUNNING) {
            return;
        }
        state = RunState.SUCCEEDED;
        finishedAt = Instant.now();
    }

    public synchronized void onSubmitted(String externalId) {
        this.externalId = externalId;
    }

    public synchronized void onProvisioned(String did, String participantContextId, String onboardingProcessId) {
        this.did = did;
        this.participantContextId = participantContextId;
        this.onboardingProcessId = onboardingProcessId;
    }

    /**
     * An externally hosted participant's onboarding outcome: it brought its own identity, and this
     * environment provisioned nothing for it — so there is no participant context id, and its
     * absence is the normal case rather than a missing value.
     */
    public synchronized void onExternallyOnboarded(String did, String onboardingProcessId) {
        this.did = did;
        this.onboardingProcessId = onboardingProcessId;
    }

    public synchronized void verificationParticipant(VerificationParticipant participant) {
        this.verificationParticipant = participant;
    }

    public synchronized void checklist(List<ChecklistItem> checklist) {
        this.checklist = List.copyOf(checklist);
    }

    public synchronized List<ChecklistItem> checklist() {
        return checklist;
    }

    public synchronized Snapshot snapshot() {
        var stepSnapshots = new ArrayList<StepSnapshot>();
        steps.forEach((step, record) -> stepSnapshots.add(
                new StepSnapshot(step, record.status, record.detail, record.startedAt, record.finishedAt)));
        return new Snapshot(id, dataspace, useCase, state, startedAt, finishedAt,
                new ParticipantInfo(name, shortName, memberId, uniqueId, externalId, did, participantContextId,
                        onboardingProcessId, externallyHosted()),
                verificationParticipant, List.copyOf(stepSnapshots), checklist, failureReason, failedStep);
    }

    public synchronized Summary summary() {
        var current = steps.entrySet().stream()
                .filter(entry -> entry.getValue().status == StepStatus.RUNNING)
                .map(java.util.Map.Entry::getKey)
                .findFirst()
                .orElse(null);
        return new Summary(id, dataspace, useCase, state, current, startedAt, finishedAt, name, shortName,
                memberId, externalId, externallyHosted());
    }

    private static final class StepRecord {
        private StepStatus status = StepStatus.PENDING;
        private String detail;
        private Instant startedAt;
        private Instant finishedAt;
    }

    /** Immutable full view of a run, serialized to the UI as-is. */
    public record Snapshot(
            String id,
            String dataspace,
            String useCase,
            RunState state,
            Instant startedAt,
            Instant finishedAt,
            ParticipantInfo participant,
            VerificationParticipant verificationParticipant,
            List<StepSnapshot> steps,
            List<ChecklistItem> checklist,
            String failureReason,
            RunStep failedStep) {
    }

    /**
     * The participant-under-test: the submitted identity plus what onboarding resolved. An
     * externally hosted one carries its DID from the start and never gets a participant context
     * id — {@code externallyHosted} is what tells a reader that null apart from "not yet".
     */
    public record ParticipantInfo(
            String name,
            String shortName,
            String memberId,
            String uniqueId,
            String externalId,
            String did,
            String participantContextId,
            String onboardingProcessId,
            boolean externallyHosted) {
    }

    public record StepSnapshot(RunStep step, StepStatus status, String detail, Instant startedAt, Instant finishedAt) {
    }

    /** Slim list-view row. */
    public record Summary(
            String id,
            String dataspace,
            String useCase,
            RunState state,
            RunStep currentStep,
            Instant startedAt,
            Instant finishedAt,
            String name,
            String shortName,
            String memberId,
            String externalId,
            boolean externallyHosted) {
    }
}
