package com.metaform.cxve.verification.adapter.in.web;

import com.metaform.cxve.verification.application.VerificationParticipantService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The permanent verification participant's dashboard card. */
@RestController
@RequestMapping("/api/verification-participant")
public class VerificationParticipantController {

    private final VerificationParticipantService participantService;

    public VerificationParticipantController(VerificationParticipantService participantService) {
        this.participantService = participantService;
    }

    /** Side-effect-free status: exists / membership record / offer seeded. */
    @GetMapping
    public VerificationParticipantService.Status get() {
        return participantService.status();
    }

    /**
     * Idempotent ensure: rediscovers or onboards the participant and seeds its permanent inbox
     * offer. SYNCHRONOUS — a first-time ensure runs the whole onboarding and can take minutes;
     * the UI shows a spinner. 200 whether created or found.
     */
    @PostMapping
    public VerificationParticipantService.Status ensure() {
        participantService.ensure();
        return participantService.status();
    }
}
