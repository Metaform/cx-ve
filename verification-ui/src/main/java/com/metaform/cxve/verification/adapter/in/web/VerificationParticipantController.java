package com.metaform.cxve.verification.adapter.in.web;

import com.metaform.cxve.verification.application.VerificationParticipantService;
import java.util.NoSuchElementException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** The dashboard card of a dataspace's permanent verification participant. */
@RestController
@RequestMapping("/api/verification-participant")
public class VerificationParticipantController {

    private final VerificationParticipantService participantService;

    public VerificationParticipantController(VerificationParticipantService participantService) {
        this.participantService = participantService;
    }

    /** Side-effect-free status: exists / membership record / offer seeded. */
    @GetMapping
    public VerificationParticipantService.Status get(@RequestParam String dataspace) {
        return participantService.status(dataspace);
    }

    /**
     * Idempotent ensure: rediscovers or onboards the dataspace's participant and seeds its
     * permanent inbox offer. SYNCHRONOUS — a first-time ensure runs the whole onboarding and can
     * take minutes; the UI shows a spinner. 200 whether created or found.
     */
    @PostMapping
    public VerificationParticipantService.Status ensure(@RequestParam String dataspace) {
        participantService.ensure(dataspace);
        return participantService.status(dataspace);
    }

    /** A dataspace this environment has no profile of. */
    @ExceptionHandler(NoSuchElementException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public String notFound(NoSuchElementException e) {
        return e.getMessage();
    }
}
