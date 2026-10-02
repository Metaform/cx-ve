package com.metaform.cxve.verification.application;

import com.metaform.cxve.verification.adapter.out.hub.MembershipHubClient;
import com.metaform.cxve.verification.domain.model.VerificationRun;
import java.util.Comparator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Holds the runs and executes them asynchronously. Runs live in memory only (v1): a restart
 * forgets them — the verification participant survives in the hub's database and every event in
 * the tracker's ledger, so nothing durable is lost, but an in-flight run simply dies with the
 * pod. Platform-side leftovers of such a run (assets, negotiations) are run-id-scoped and inert.
 */
@Service
public class RunService {

    private final ConcurrentHashMap<String, VerificationRun> runs = new ConcurrentHashMap<>();
    private final DataspaceCatalog catalog;
    private final ExecutorService runExecutor;
    private final MembershipHubClient hub;
    private final ObjectMapper mapper;

    public RunService(DataspaceCatalog catalog,
                      ExecutorService runExecutor,
                      MembershipHubClient hub,
                      ObjectMapper mapper) {
        this.catalog = catalog;
        this.runExecutor = runExecutor;
        this.hub = hub;
        this.mapper = mapper;
    }

    /**
     * Creates and starts a run of a use case in a dataspace — both checked against the
     * {@link DataspaceCatalog}, which also picks the flow. A {@code did} selects what is being
     * verified: given one, the participant is a third-party system already running under that
     * identity and only its own half of the exchange is driven from here; without one, the
     * participant is onboarded into this environment and driven end to end.
     *
     * <p>The member id is REQUIRED (the catalog checks it against the dataspace's format). This
     * environment issues the participant's member credential for exactly that value (Catena-X: the
     * BpnCredential), and an external system's is agreed with its operator — Certo checks its
     * certificates against it — so nothing here may substitute a derived placeholder for a missing
     * one. The exception would be an external participant of a dataspace that ASSIGNS its member id
     * on onboarding (none does today): it comes without one, and the run adopts the assigned id.
     *
     * <p>The remaining absent inputs are still derived: the short name from the run id, the
     * registration's unique id deterministically from the short name (the e2e suite's VAT-id
     * formula), so repeated runs never collide on identity.
     *
     * @throws DataspaceCatalog.InvalidRunRequestException for a request the catalog refuses
     */
    public VerificationRun.Snapshot start(String dataspace, String useCase, String name, String shortName,
                                          String memberId, String did) {
        var resolvedMemberId = hasText(memberId) ? memberId.trim() : null;
        var declaredDid = hasText(did) ? did.trim() : null;
        var flow = catalog.resolve(dataspace, useCase, resolvedMemberId, declaredDid != null);
        var runId = UUID.randomUUID().toString().substring(0, 8);
        var resolvedShortName = hasText(shortName) ? shortName.trim() : "put-" + runId;
        var resolvedName = hasText(name) ? name.trim() : "Participant " + runId;
        var run = new VerificationRun(runId, dataspace, useCase, resolvedName, resolvedShortName, resolvedMemberId,
                VatIdDeriver.vatIdFor(resolvedShortName), declaredDid, flow.steps(declaredDid != null));
        runs.put(runId, run);
        runExecutor.submit(() -> flow.execute(run));
        return run.snapshot();
    }

    public List<VerificationRun.Summary> list() {
        return runs.values().stream()
                .map(VerificationRun::summary)
                .sorted(Comparator.comparing(VerificationRun.Summary::startedAt).reversed())
                .toList();
    }

    public VerificationRun.Snapshot get(String id) {
        return run(id).snapshot();
    }

    /**
     * The run participant's eventlog rollup, proxied from the hub. Empty until the onboarding
     * process id is known and the tracker has opened the participant; hub hiccups (retryable
     * errors) also yield the empty rollup — the UI polls, the next tick heals it.
     */
    public JsonNode events(String id) {
        var processId = run(id).onboardingProcessId();
        if (processId == null) {
            return emptyRollup();
        }
        try {
            return hub.eventlog(processId).orElseGet(this::emptyRollup);
        } catch (Poller.RetryException e) {
            return emptyRollup();
        }
    }

    private VerificationRun run(String id) {
        var run = runs.get(id);
        if (run == null) {
            throw new NoSuchElementException("No run with id " + id);
        }
        return run;
    }

    private JsonNode emptyRollup() {
        var rollup = mapper.createObjectNode();
        rollup.set("events", mapper.createArrayNode());
        return rollup;
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
