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
    private final CertificateExchangeFlow flow;
    private final ExecutorService runExecutor;
    private final MembershipHubClient hub;
    private final ObjectMapper mapper;

    public RunService(CertificateExchangeFlow flow,
                      ExecutorService runExecutor,
                      MembershipHubClient hub,
                      ObjectMapper mapper) {
        this.flow = flow;
        this.runExecutor = runExecutor;
        this.hub = hub;
        this.mapper = mapper;
    }

    /**
     * Creates and starts a run. Absent inputs are derived: the short name from the run id, the
     * BPN/VAT deterministically from the short name (the e2e suite's formula) — so repeated runs
     * never collide on identity, while a caller-pinned identity is honored as-is.
     */
    public VerificationRun.Snapshot start(String name, String shortName, String bpn) {
        var runId = UUID.randomUUID().toString().substring(0, 8);
        var resolvedShortName = hasText(shortName) ? shortName.trim() : "put-" + runId;
        var resolvedName = hasText(name) ? name.trim() : "Participant " + runId;
        var resolvedBpn = hasText(bpn) ? bpn.trim() : BpnDeriver.bpnFor(resolvedShortName);
        var run = new VerificationRun(runId, resolvedName, resolvedShortName, resolvedBpn,
                BpnDeriver.vatIdFor(resolvedShortName));
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
