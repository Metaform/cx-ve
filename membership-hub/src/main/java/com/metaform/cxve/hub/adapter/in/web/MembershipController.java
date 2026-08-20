package com.metaform.cxve.hub.adapter.in.web;

import com.metaform.cxve.hub.application.MembershipService;
import com.metaform.cxve.hub.domain.model.MemberData;
import com.metaform.cxve.hub.domain.model.Membership;
import jakarta.validation.Valid;
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

/**
 * The hub's own API: submit a member, read a member back. The returned {@link Membership} record
 * IS the correlated view — the registration side (externalId, onboarding process id, state) and
 * the provisioning side (tenant, profile, participant context) on one record.
 */
@RestController
@RequestMapping("/api/members")
public class MembershipController {

    private final MembershipService membershipService;

    public MembershipController(MembershipService membershipService) {
        this.membershipService = membershipService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Membership onboard(@Valid @RequestBody MemberData data) {
        return membershipService.onboard(data);
    }

    /** Reading a PROVISIONING member refreshes it against the Tenant Manager (lazy poll). */
    @GetMapping("/{externalId}")
    public Membership get(@PathVariable String externalId) {
        return membershipService.get(externalId);
    }

    @ExceptionHandler(NoSuchElementException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public String notFound(NoSuchElementException e) {
        return e.getMessage();
    }
}
