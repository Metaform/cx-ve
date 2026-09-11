package com.metaform.cxve.verification.domain.model;

import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;

/**
 * One verification run: the participant-under-test's identity plus the live step ledger the
 * executing flow writes and the UI polls. Mutated only by the run's executor thread, read by web
 * threads — every access goes through synchronized methods, and readers only ever see immutable
 * {@link Snapshot}s. State is in-memory by design (v1): a pod restart loses the run record, but
 * nothing durable — the participant lives in the hub's database and the events in the tracker's.
 */
public class VerificationRun {

    private final String id;
    private final Instant startedAt = Instant.now();
    private final String name;
    private final String shortName;
    private final String bpn;
    private final String vatId;
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

    public VerificationRun(String id, String name, String shortName, String bpn, String vatId) {
        this.id = id;
        this.name = name;
        this.shortName = shortName;
        this.bpn = bpn;
        this.vatId = vatId;
        for (var step : RunStep.values()) {
            steps.put(step, new StepRecord());
        }
    }

    public String id() {
        return id;
    }

    public String name() {
        return name;
    }

    public String shortName() {
        return shortName;
    }

    public String bpn() {
        return bpn;
    }

    public String vatId() {
        return vatId;
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
        return new Snapshot(id, state, startedAt, finishedAt,
                new ParticipantInfo(name, shortName, bpn, vatId, externalId, did, participantContextId, onboardingProcessId),
                verificationParticipant, List.copyOf(stepSnapshots), checklist, failureReason, failedStep);
    }

    public synchronized Summary summary() {
        var current = steps.entrySet().stream()
                .filter(entry -> entry.getValue().status == StepStatus.RUNNING)
                .map(java.util.Map.Entry::getKey)
                .findFirst()
                .orElse(null);
        return new Summary(id, state, current, startedAt, finishedAt, name, shortName, bpn, externalId);
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

    /** The participant-under-test: the submitted identity plus what provisioning resolved. */
    public record ParticipantInfo(
            String name,
            String shortName,
            String bpn,
            String vatId,
            String externalId,
            String did,
            String participantContextId,
            String onboardingProcessId) {
    }

    public record StepSnapshot(RunStep step, StepStatus status, String detail, Instant startedAt, Instant finishedAt) {
    }

    /** Slim list-view row. */
    public record Summary(
            String id,
            RunState state,
            RunStep currentStep,
            Instant startedAt,
            Instant finishedAt,
            String name,
            String shortName,
            String bpn,
            String externalId) {
    }
}
