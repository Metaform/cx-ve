package com.metaform.cxve.verification.adapter.in.web;

import com.metaform.cxve.verification.application.DataspaceCatalog;
import com.metaform.cxve.verification.application.RunService;
import com.metaform.cxve.verification.domain.model.VerificationRun;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
import java.util.NoSuchElementException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/**
 * Verification runs, as the Angular UI drives them: start one, poll its snapshot + eventlog
 * every couple of seconds until it is terminal. The snapshot IS the wire format — immutable
 * records straight off the run aggregate.
 */
@RestController
@RequestMapping("/api/runs")
public class RunController {

    private final RunService runService;

    public RunController(RunService runService) {
        this.runService = runService;
    }

    /**
     * A run of a {@code useCase} (e.g. {@code ccm}) in a {@code dataspace} (e.g. {@code catena-x}),
     * both as the catalog lists them. The {@code memberId} — the participant's id in that
     * dataspace, the BPN in Catena-X, the DECADE-X-ID in DECADE-X — is mandatory unless the
     * dataspace assigns it to an external participant (none does today); the catalog enforces
     * that, so a run never reaches the flow with an identifier this environment invented for it.
     * {@code name} and {@code shortName} stay optional and are defaulted (see
     * {@link RunService#start}). The {@code did} is the one with a consequence beyond naming:
     * supplying it declares that the participant is a third-party system already running under
     * that identity, and selects the run that only drives this environment's own half of the
     * exchange.
     */
    public record StartRunRequest(@NotBlank String dataspace, @NotBlank String useCase, String name,
                                  String shortName, String memberId, String did) {
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public VerificationRun.Snapshot start(@Valid @RequestBody StartRunRequest request) {
        return runService.start(request.dataspace(), request.useCase(), request.name(), request.shortName(),
                request.memberId(), request.did());
    }

    @GetMapping
    public List<VerificationRun.Summary> list() {
        return runService.list();
    }

    @GetMapping("/{id}")
    public VerificationRun.Snapshot get(@PathVariable String id) {
        return runService.get(id);
    }

    /** The participant-under-test's eventlog rollup (proxied from the hub); empty until known. */
    @GetMapping("/{id}/events")
    public JsonNode events(@PathVariable String id) {
        return runService.events(id);
    }

    /** A dataspace, use case or member id the catalog refuses. */
    @ExceptionHandler(DataspaceCatalog.InvalidRunRequestException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public String invalid(DataspaceCatalog.InvalidRunRequestException e) {
        return e.getMessage();
    }

    @ExceptionHandler(NoSuchElementException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public String notFound(NoSuchElementException e) {
        return e.getMessage();
    }
}
