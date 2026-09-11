package com.metaform.cxve.verification.adapter.in.web;

import com.metaform.cxve.verification.application.RunService;
import com.metaform.cxve.verification.domain.model.VerificationRun;
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

    /** All fields are optional — absent ones are derived (see {@link RunService#start}). */
    public record StartRunRequest(String name, String shortName, String bpn) {
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public VerificationRun.Snapshot start(@RequestBody(required = false) StartRunRequest request) {
        var body = request == null ? new StartRunRequest(null, null, null) : request;
        return runService.start(body.name(), body.shortName(), body.bpn());
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

    @ExceptionHandler(NoSuchElementException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public String notFound(NoSuchElementException e) {
        return e.getMessage();
    }
}
