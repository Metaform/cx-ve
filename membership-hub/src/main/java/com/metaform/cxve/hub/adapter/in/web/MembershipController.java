package com.metaform.cxve.hub.adapter.in.web;

import com.metaform.cxve.hub.application.DuplicateMembershipException;
import com.metaform.cxve.hub.application.MembershipService;
import com.metaform.cxve.hub.domain.model.MemberData;
import com.metaform.cxve.hub.domain.model.Membership;
import jakarta.validation.Valid;
import java.util.List;
import java.util.NoSuchElementException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
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

    /**
     * Starts the member and returns as soon as its first leg is under way: PROVISIONING for a
     * member this environment hosts (its resources are deployed before it is registered), SUBMITTED
     * for one that brought its own DID. The rest arrives asynchronously — poll {@link #get}. A DID
     * or BPN a live membership already holds is refused with 409, before anything is deployed.
     */
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

    /**
     * The memberships registered under a BPN or a DID — a list, because rejected/failed attempts
     * retire neither. Exactly one filter must be given (there is deliberately no unpaged
     * list-everything, and two filters would leave the intended semantics of the combination
     * ambiguous), and unlike {@link #get} this is a plain read without a Tenant Manager refresh.
     *
     * <p>The DID filter is what lets a caller find the membership an externally hosted member
     * already has: its DID is the identity its operator supplies, and re-onboarding it would be
     * declined by the Onboarding API as a duplicate registration rather than repeated.
     */
    @GetMapping
    public List<Membership> find(@RequestParam(required = false) String bpn,
                                 @RequestParam(required = false) String did) {
        if (isBlank(bpn) == isBlank(did)) {
            throw new IllegalArgumentException("Exactly one of the 'bpn' and 'did' filters must be given");
        }
        return isBlank(did) ? membershipService.findByBpn(bpn) : membershipService.findByDid(did);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    @ExceptionHandler(NoSuchElementException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public String notFound(NoSuchElementException e) {
        return e.getMessage();
    }

    /** The DID or BPN is already taken by a live membership — nothing was created for this call. */
    @ExceptionHandler(DuplicateMembershipException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public String conflict(DuplicateMembershipException e) {
        return e.getMessage();
    }

    /** A filter combination the endpoint cannot express — reported like a missing parameter. */
    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public String badRequest(IllegalArgumentException e) {
        return e.getMessage();
    }
}
