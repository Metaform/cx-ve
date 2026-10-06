package com.metaform.cxve.verification.adapter.in.web;

import com.metaform.cxve.verification.application.VerificationException;
import com.metaform.cxve.verification.application.VerificationParticipantService;
import java.util.NoSuchElementException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    private static final Logger log = LoggerFactory.getLogger(VerificationParticipantController.class);

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

    /**
     * The ensure failed on the way (onboarding, provisioning, seeding) — the reason, for the card,
     * and logged: a resolved exception is not logged by Spring itself.
     */
    @ExceptionHandler(VerificationException.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public String failed(VerificationException e) {
        log.error("Ensuring the verification participant failed: {}", e.getMessage(), e);
        return e.getMessage();
    }

    /** A dataspace this environment has no profile of. */
    @ExceptionHandler(NoSuchElementException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public String notFound(NoSuchElementException e) {
        return e.getMessage();
    }
}
